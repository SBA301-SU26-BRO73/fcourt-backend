package com.fcourt.auth.staff;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StaffReactivation {
    private final JdbcTemplate jdbc;private final ManagedStaffBoundary staff;private final Clock clock;
    public StaffReactivation(JdbcTemplate jdbc,ManagedStaffBoundary staff,Clock clock){this.jdbc=jdbc;this.staff=staff;this.clock=clock;}
    public Account me(Jwt jwt) {return account(Long.parseLong(jwt.getSubject()),false);}
    @Transactional(timeout=15)
    public Account reactivate(Jwt jwt,long user,long branch,String key) {
        long actor=Long.parseLong(jwt.getSubject());var admin=account(actor,false);
        if(!"ADMIN".equals(admin.role())||!"ACTIVE".equals(admin.status()))throw forbidden();
        UUID command;
        try{if(!key.startsWith("staff:"+actor+":"))throw new IllegalArgumentException();command=UUID.fromString(key.substring(key.lastIndexOf(':')+1));}
        catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid command key");}
        staff.require(user,branch,jwt.getTokenValue());
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",actor+":"+command);
        var replay=jdbc.queryForList("SELECT user_id,branch_id FROM staff_reactivation_commands WHERE actor_id=? AND command_key=?",actor,command);
        if(!replay.isEmpty()) {
            if(((Number)replay.getFirst().get("user_id")).longValue()!=user||((Number)replay.getFirst().get("branch_id")).longValue()!=branch)throw conflict();
            return account(user,false);
        }
        var target=account(user,true);
        if(!"STAFF".equals(target.role())||(!"INACTIVE".equals(target.status())&&!"ACTIVE".equals(target.status())))throw conflict();
        if("INACTIVE".equals(target.status())){
            jdbc.update("UPDATE users SET status='ACTIVE',updated_at=? WHERE id=?",java.sql.Timestamp.from(clock.instant()),user);
            jdbc.update("INSERT INTO user_account_action_logs(target_user_id,performed_by_id,action,previous_status,new_status,reason,created_at) VALUES (?,?,'STAFF_REACTIVATE','INACTIVE','ACTIVE','Branch staff restored',?)",user,actor,LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
        }
        jdbc.update("INSERT INTO staff_reactivation_commands(actor_id,command_key,user_id,branch_id) VALUES (?,?,?,?)",actor,command,user,branch);
        return account(user,false);
    }
    private Account account(long id,boolean lock) {
        var rows=jdbc.query("SELECT id,email,full_name,phone,role,status FROM users WHERE id=? AND deleted_at IS NULL"+(lock?" FOR UPDATE":""),
            (r,n)->new Account(r.getLong("id"),r.getString("email"),r.getString("full_name"),r.getString("phone"),r.getString("role"),r.getString("status")),id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Account not found");return rows.getFirst();
    }
    private static ResponseStatusException forbidden(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"Active branch administrator required");}
    private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"Account or command conflict");}
    public record Account(long id,String email,String fullName,String phone,String role,String status) { }
}
