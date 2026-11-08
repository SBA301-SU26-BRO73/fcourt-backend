package com.fcourt.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class FullCourtQuoteTest {
    FullCourtCatalog catalog=mock(FullCourtCatalog.class);
    Clock clock=Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"),ZoneOffset.UTC);
    FullCourtQuote service=new FullCourtQuote(catalog,clock,new BigDecimal("2"),new BigDecimal("12.5"));
    Jwt admin=Jwt.withTokenValue("fixture").header("alg","test").subject("10").claim("roles",List.of("ADMIN")).build();
    LocalDate date=LocalDate.of(2030,1,2);
    FullCourtQuote.Request request(List<Long> ids){return new FullCourtQuote.Request(7,ids,date,LocalTime.of(8,0),LocalTime.of(9,0));}
    List<CounterDependencies.Slot> slots(String status) {
        return List.of(new CounterDependencies.Slot(1,LocalTime.of(8,0),LocalTime.of(8,30),new BigDecimal("10000.01"),status),
                new CounterDependencies.Slot(2,LocalTime.of(8,30),LocalTime.of(9,0),new BigDecimal("10000.01"),status));
    }
    @BeforeEach void configure() {
        for(long court:List.of(1L,2L)) {
            when(catalog.slots(court,date)).thenReturn(slots("AVAILABLE"));
            when(catalog.availability(court,date)).thenReturn(slots("AVAILABLE"));
        }
    }
    @Test void totalsAreServerPricedAndDiscountRoundsDownExactly() {
        var quote=service.quote(request(List.of(1L,2L)),admin);
        assertThat(quote.subtotal()).isEqualByComparingTo("40000.04");
        assertThat(quote.courtHours()).isEqualByComparingTo("2");
        assertThat(quote.discountAmount()).isEqualByComparingTo("5000.00");
        assertThat(quote.total()).isEqualByComparingTo("35000.04");
        assertThat(quote.reserved()).isFalse();assertThat(quote.reservationState()).isEqualTo("ATOMIC_AUTHORITY_REQUIRED");
        verify(catalog).requireOwned(7,List.of(1L,2L),admin);
    }
    @Test void belowThresholdHasNoDiscount() {
        var quote=service.quote(request(List.of(1L)),admin);
        assertThat(quote.discountPercent()).isZero();assertThat(quote.total()).isEqualByComparingTo("20000.02");
    }
    @Test void unauthorizedUsersNeverQueryVenueData() {
        clearInvocations(catalog);
        var customer=Jwt.withTokenValue("fixture").header("alg","test").subject("10").claim("roles",List.of("CUSTOMER")).build();
        assertThatThrownBy(()->service.quote(request(List.of(1L)),customer)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(catalog);
    }
    @Test void duplicateCourtsAndPastWindowsAreRejectedBeforeCatalog() {
        clearInvocations(catalog);
        assertThatThrownBy(()->service.quote(request(List.of(1L,1L)),admin)).isInstanceOf(ResponseStatusException.class);
        var past=new FullCourtQuote.Request(7,List.of(1L),LocalDate.of(2029,12,31),LocalTime.of(8,0),LocalTime.of(9,0));
        assertThatThrownBy(()->service.quote(past,admin)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(catalog);
    }
    @Test void liveHoldOrCanonicalOutOfServiceRejectWholeQuote() {
        when(catalog.availability(2,date)).thenReturn(slots("HOLD"));
        assertThatThrownBy(()->service.quote(request(List.of(1L,2L)),admin)).isInstanceOf(ResponseStatusException.class);
        when(catalog.slots(1,date)).thenReturn(slots("OUT_OF_SERVICE"));
        assertThatThrownBy(()->service.quote(request(List.of(1L)),admin)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void missingOrDuplicateCanonicalSlotsNeverProducePartialPrice() {
        when(catalog.slots(1,date)).thenReturn(slots("AVAILABLE").subList(0,1));
        assertThatThrownBy(()->service.quote(request(List.of(1L)),admin)).isInstanceOf(ResponseStatusException.class);
        var duplicate=slots("AVAILABLE").getFirst();
        when(catalog.slots(1,date)).thenReturn(List.of(duplicate,duplicate));
        assertThatThrownBy(()->service.quote(request(List.of(1L)),admin)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void pricingExposesTheExactSlotsItPricedForTheReservationSaga() {
        var priced=service.price(request(List.of(1L,2L)),admin);
        assertThat(priced.quote().total()).isEqualByComparingTo("35000.04");
        assertThat(priced.slots().keySet()).containsExactly(1L,2L);
        assertThat(priced.slots().get(2L)).extracting(CounterDependencies.Slot::slotId).containsExactly(1L,2L);
    }
    @Test void invalidServerPolicyFailsAtStartup() {
        assertThatThrownBy(()->new FullCourtQuote(catalog,clock,BigDecimal.ONE,new BigDecimal("101"))).isInstanceOf(IllegalArgumentException.class);
    }
}
