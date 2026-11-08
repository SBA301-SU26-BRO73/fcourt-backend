package com.fcourt.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** A server-priced preview. It cannot promise a reservation until the shared authority exists. */
@Service
class FullCourtQuote {
    private final FullCourtCatalog catalog;
    private final Clock clock;
    private final BigDecimal minimumHours, percent;
    FullCourtQuote(FullCourtCatalog catalog, Clock clock,
            @Value("${booking.full-court.minimum-hours:4}") BigDecimal minimumHours,
            @Value("${booking.full-court.discount-percent:0}") BigDecimal percent) {
        if(minimumHours==null || minimumHours.signum()<=0 || percent==null || percent.signum()<0 || percent.compareTo(BigDecimal.valueOf(100))>0 || percent.scale()>2)
            throw new IllegalArgumentException("Invalid full-court pricing configuration");
        this.catalog=catalog; this.clock=clock; this.minimumHours=minimumHours; this.percent=percent;
    }
    record Request(@Positive long branchId, @NotEmpty @Size(max=50) List<@NotNull @Positive Long> courtIds,
            @NotNull LocalDate date, @NotNull LocalTime startTime, @NotNull LocalTime endTime) {}
    record Quote(long branchId, List<Long> courtIds, LocalDate date, LocalTime startTime, LocalTime endTime,
            BigDecimal courtHours, BigDecimal subtotal, BigDecimal discountPercent, BigDecimal discountAmount,
            BigDecimal total, String currency, boolean reserved, String reservationState) {}
    Quote quote(Request request, Jwt jwt) {
        requireAdmin(jwt);
        validate(request);
        catalog.requireOwned(request.branchId(), request.courtIds(), jwt);
        BigDecimal subtotal=BigDecimal.ZERO;
        int count=0;
        for(long court:request.courtIds()) {
            var canonical=catalog.slots(court,request.date());
            var available=catalog.availability(court,request.date());
            var byStart=new HashMap<LocalTime,CounterDependencies.Slot>();
            for(var slot:canonical) if(byStart.put(slot.startTime(),slot)!=null) throw unavailable();
            for(var start=request.startTime();start.isBefore(request.endTime());start=start.plusMinutes(30)) {
                var slot=byStart.get(start);
                if(slot==null || !slot.endTime().equals(start.plusMinutes(30)) || !slot.status().equals("AVAILABLE")) throw conflict();
                var live=available.stream().filter(s->s.slotId()==slot.slotId()).toList();
                if(live.size()!=1 || !live.getFirst().status().equals("AVAILABLE")) throw conflict();
                if(slot.price()==null || slot.price().signum()<0 || slot.price().scale()>2) throw unavailable();
                subtotal=subtotal.add(slot.price());count++;
            }
        }
        if(subtotal.compareTo(new BigDecimal("99999999.99"))>0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Package exceeds supported amount");
        var hours=BigDecimal.valueOf(count).divide(BigDecimal.valueOf(2));
        var applied=hours.compareTo(minimumHours)>=0?percent:BigDecimal.ZERO;
        var discount=subtotal.multiply(applied).divide(BigDecimal.valueOf(100),2,RoundingMode.DOWN);
        return new Quote(request.branchId(),List.copyOf(request.courtIds()),request.date(),request.startTime(),request.endTime(),hours,
                subtotal.setScale(2),applied,discount,subtotal.subtract(discount).setScale(2),"VND",false,"ATOMIC_AUTHORITY_REQUIRED");
    }
    private void validate(Request request) {
        if(request==null || request.branchId()<=0 || request.courtIds()==null || request.courtIds().isEmpty() || request.courtIds().size()>50
                || request.courtIds().stream().anyMatch(id->id==null || id<=0) || new HashSet<>(request.courtIds()).size()!=request.courtIds().size()
                || request.date()==null || request.startTime()==null || request.endTime()==null
                || request.startTime().getMinute()%30!=0 || request.endTime().getMinute()%30!=0
                || request.startTime().getSecond()!=0 || request.endTime().getSecond()!=0 || request.startTime().getNano()!=0 || request.endTime().getNano()!=0
                || !request.endTime().isAfter(request.startTime())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid full-court window or courts");
        var now=LocalDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Ho_Chi_Minh"));
        if(!request.date().atTime(request.startTime()).isAfter(now) || request.date().isAfter(now.toLocalDate().plusDays(31)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Window must be future and within 31 days");
    }
    static void requireAdmin(Jwt jwt) {
        BookingQueries.actor(jwt);
        Object roles=jwt.getClaims().get("roles");
        if(!(roles instanceof Collection<?> c && c.contains("ADMIN")) && !"ADMIN".equals(jwt.getClaimAsString("role")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Branch administrator required");
    }
    static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"Selected court window unavailable");}
    static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Full-court authority unavailable");}
}

@RestController
class FullCourtController {
    private final FullCourtQuote quotes;
    FullCourtController(FullCourtQuote quotes){this.quotes=quotes;}
    @PostMapping("/api/admin/bookings/full-court/quote")
    ResponseEntity<FullCourtQuote.Quote> quote(@Valid @RequestBody FullCourtQuote.Request request,@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(quotes.quote(request,jwt));
    }
    @PostMapping("/api/admin/bookings/full-court")
    ResponseEntity<ProblemDetail> reserve(@Valid @RequestBody FullCourtQuote.Request request,@AuthenticationPrincipal Jwt jwt) {
        FullCourtQuote.requireAdmin(jwt);
        var problem=ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,"Atomic cross-service reservation is not integrated; no booking or lock was created");
        problem.setProperty("code","FULL_COURT_ATOMIC_AUTHORITY_REQUIRED");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore()).body(problem);
    }
}
