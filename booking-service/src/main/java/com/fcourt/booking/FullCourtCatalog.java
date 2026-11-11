package com.fcourt.booking;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Component
class FullCourtCatalog {
    private final RestClient facility;
    private final CounterDependencies schedules;
    FullCourtCatalog(RestClient.Builder builder, CounterDependencies schedules,@Value("${FACILITY_URL:http://localhost:8083}") String url) {
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(2));factory.setReadTimeout(Duration.ofSeconds(3));
        this.facility=builder.clone().requestFactory(factory).baseUrl(url).build(); this.schedules=schedules;
    }
    /** Display names of a verified venue, copied into the package bookings (no cross-service reads later). */
    record Venue(String branchName, Map<Long,String> courtNames) {}
    Venue requireOwned(long branch, List<Long> courts, Jwt jwt) {
        try {
            var owned=facility.get().uri("/api/admin/branches/{id}",branch).header(HttpHeaders.AUTHORIZATION,"Bearer "+jwt.getTokenValue()).retrieve().body(Branch.class);
            if(owned==null || owned.id()!=branch || owned.adminId()!=BookingQueries.actor(jwt) || !"ACTIVE".equals(owned.status())) throw BookingQueries.missing();
            var rows=facility.get().uri("/api/admin/branches/{id}/courts",branch).header(HttpHeaders.AUTHORIZATION,"Bearer "+jwt.getTokenValue()).retrieve().body(new ParameterizedTypeReference<List<Court>>(){});
            if(rows==null || rows.size()>500) throw FullCourtQuote.unavailable();
            var allowed=new HashSet<Long>();
            var names=new HashMap<Long,String>();
            for(var court:rows) if(court.branchId()==branch && "ACTIVE".equals(court.status())) {allowed.add(court.id());names.put(court.id(),court.name());}
            if(!allowed.containsAll(courts)) throw BookingQueries.missing();
            return new Venue(owned.name(),names);
        } catch(HttpClientErrorException e) {
            if(Set.of(401,403,404).contains(e.getStatusCode().value())) throw BookingQueries.missing();
            throw FullCourtQuote.unavailable();
        } catch(RestClientException e) {throw FullCourtQuote.unavailable();}
    }
    List<CounterDependencies.Slot> slots(long court,LocalDate date){return schedules.canonicalSlots(court,date);}
    List<CounterDependencies.Slot> availability(long court,LocalDate date){return schedules.slots(court,date);}
    @JsonIgnoreProperties(ignoreUnknown=true) record Branch(long id,long adminId,String status,String name){}
    @JsonIgnoreProperties(ignoreUnknown=true) record Court(long id,long branchId,String status,String name){}
}
