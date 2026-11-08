package com.fcourt.discovery.component;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

public final class ReviewIdentity {
    private ReviewIdentity() {}
    public static long require(Jwt jwt, String role) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        long actor;
        try { actor = Long.parseLong(jwt.getSubject()); if (actor <= 0) throw new NumberFormatException(); }
        catch (NumberFormatException error) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
        Object claim = jwt.getClaims().get("roles");
        Collection<?> roles = claim instanceof Collection<?> values ? values : List.of(Objects.toString(jwt.getClaims().get("role"), ""));
        if (!roles.contains(role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền thực hiện thao tác này");
        return actor;
    }
}
