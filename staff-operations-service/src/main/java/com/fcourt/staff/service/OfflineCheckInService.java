package com.fcourt.staff.service;

import com.fcourt.staff.integration.BookingGateway;
import com.fcourt.staff.persistence.BookingCommandStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OfflineCheckInService {
    private final StaffService staff; private final BookingGateway booking;
    private final BookingCommandStore commands; private final OfflinePermitCodec codec;
    private final Clock clock; private final ZoneId zone;
    public OfflineCheckInService(StaffService staff,BookingGateway booking,BookingCommandStore commands,
                                OfflinePermitCodec codec,Clock clock,ZoneId zone) {
        this.staff=staff;this.booking=booking;this.commands=commands;this.codec=codec;this.clock=clock;this.zone=zone;
    }
    public Snapshot snapshot(Jwt jwt) {
        var assigned=staff.assigned(jwt);long actor=Long.parseLong(jwt.getSubject());
        var today=LocalDate.now(clock.withZone(zone));var now=clock.instant();
        var roster=booking.offlineTickets(assigned.branchId(),today,jwt.getTokenValue());
        if(roster.branchId()!=assigned.branchId() || roster.tickets()==null || roster.tickets().size()>5000) throw BookingGateway.unavailable();
        var tickets=new ArrayList<Ticket>();var hashes=new HashSet<String>();var ids=new HashSet<Long>();
        for(var t:roster.tickets()) {
            if(t==null || t.bookingId()<=0 || !ids.add(t.bookingId()) || !today.equals(t.date()) || t.courtName()==null
                    || t.slotStart()==null || t.slotEnd()==null || t.checkInCode()==null || t.checkInCode().isBlank()
                    || t.checkInCode().length()>128 || !"CONFIRMED".equals(t.status())) throw BookingGateway.unavailable();
            String hash=hash(t.checkInCode().strip());if(!hashes.add(hash)) throw BookingGateway.unavailable();
            var from=t.date().atTime(t.slotStart()).atZone(zone).toInstant().minusSeconds(900);
            var endDate=t.slotEnd().equals(LocalTime.MIDNIGHT)?t.date().plusDays(1):t.date();
            var until=endDate.atTime(t.slotEnd()).atZone(zone).toInstant();
            if(!until.isAfter(from)) throw BookingGateway.unavailable();
            if(!now.isBefore(until)) continue;
            var permit=new OfflinePermitCodec.Permit(actor,assigned.branchId(),t.bookingId(),today,hash,from,until,now);
            tickets.add(new Ticket(t.bookingId(),hash,t.courtName(),t.customerName(),from,until,codec.sign(permit)));
        }
        return new Snapshot(actor,assigned.branchId(),today,now,tickets);
    }
    @Transactional(timeout=20)
    public BookingGateway.CheckInResult replay(Jwt jwt,UUID key,String permitToken,Instant occurredAt) {
        var assigned=staff.assigned(jwt);long actor=Long.parseLong(jwt.getSubject());
        var p=codec.verify(permitToken);var now=clock.instant();
        if(p.actorId()!=actor || p.branchId()!=assigned.branchId()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Offline assignment mismatch");
        if(occurredAt==null || p.validFrom()==null || p.validUntil()==null || p.issuedAt()==null || p.date()==null
                || occurredAt.isBefore(p.validFrom()) || !occurredAt.isBefore(p.validUntil())
                || occurredAt.isBefore(p.issuedAt().minusSeconds(60)) || occurredAt.isAfter(now.plusSeconds(60))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid offline admission time");
        }
        String fingerprint=commands.fingerprint(List.of(p,occurredAt));
        var replay=commands.replay(actor,key,"BookingCheckedInEvent",fingerprint,BookingGateway.CheckInResult.class);
        if(replay!=null)return replay;
        if(now.isAfter(p.validUntil().plusSeconds(86400))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Offline reconciliation expired");
        var result=booking.offlineCheckIn(new BookingGateway.OfflineCommand(p.branchId(),actor,p.bookingId(),p.date(),occurredAt),
                "staff:offline:"+actor+":"+key,jwt.getTokenValue());
        if(result.bookingId()!=p.bookingId() || result.branchId()!=p.branchId() || !p.date().equals(result.date())
                || !"CHECKED_IN".equals(result.status()) || !occurredAt.equals(result.checkedInAt())) throw BookingGateway.unavailable();
        commands.auditCheckIn(actor,key,p.bookingId(),p.branchId(),"OFFLINE",occurredAt);
        commands.complete(actor,key,"BookingCheckedInEvent",fingerprint,result,p.bookingId(),
                Map.of("booking",result,"actorId",actor,"channel","OFFLINE","occurredAt",occurredAt));
        return result;
    }
    private static String hash(String code) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("Cannot hash ticket");}
    }
    public record Snapshot(long userId,long branchId,LocalDate date,Instant generatedAt,List<Ticket> tickets) { }
    public record Ticket(long bookingId,String codeHash,String courtName,String customerName,
                         Instant validFrom,Instant validUntil,String permit) { }
}
