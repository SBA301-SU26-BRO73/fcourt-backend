package com.fcourt.payment;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Component
class BranchOwnership {
    private final RestClient facility;
    BranchOwnership(@Qualifier("facilityClient") RestClient facility) { this.facility=facility; }
    long actor(Jwt jwt) {
        try {
            long actor=Long.parseLong(jwt.getSubject());
            if (actor <= 0) throw new NumberFormatException();
            return actor;
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Admin identity unavailable");
        }
    }
    void require(long branchId,Jwt jwt) {
        long actor=actor(jwt);
        try {
            var branch=facility.get().uri("/api/admin/branches/{id}",branchId)
                    .header("Authorization","Bearer "+jwt.getTokenValue()).retrieve().body(OwnedBranch.class);
            if (branch == null || branch.id() == null || branch.adminId() == null) throw PaymentUpstreams.unavailable();
            if (branch.id() != branchId || branch.adminId() != actor) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Branch access denied");
            }
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value()==401 || e.getStatusCode().value()==403 || e.getStatusCode().value()==404) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Branch access denied");
            }
            throw PaymentUpstreams.unavailable();
        } catch (RestClientException e) { throw PaymentUpstreams.unavailable(); }
    }
    record OwnedBranch(Long id,Long adminId) { }
}
