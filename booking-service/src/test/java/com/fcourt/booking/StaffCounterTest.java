package com.fcourt.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.*;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Testcontainers
@SpringBootTest(
        properties = {
            "booking.events-enabled=false",
            "STAFF_BOOKING_SERVICE_TOKEN=fixture-staff-booking-token-not-production", "POS_BOOKING_SERVICE_TOKEN=fixture-pos-booking-secret-not-production", "booking.pos-dispatch-enabled=false"
        })
class StaffCounterTest {
    static final AtomicReference<Instant> NOW = new AtomicReference<>();
    static final LocalDate DAY = LocalDate.of(2026, 11, 1);
    static final String SECRET = "fixture-staff-booking-token-not-production";

    @org.springframework.boot.test.context.TestConfiguration
    static class Time {
        @Bean
        @Primary
        Clock counterTestClock() {
            return new Clock() {
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                public Clock withZone(ZoneId zone) {
                    return this;
                }

                public Instant instant() {
                    return NOW.get();
                }
            };
        }
    }

    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StaffCounter counter;
    @Autowired BookingPosCharges charges;
    @MockBean StaffAuthority authority;
    @MockBean CounterDependencies dependencies;
    final Jwt jwt =
            Jwt.withTokenValue("token")
                    .header("alg", "none")
                    .subject("100")
                    .claim("roles", List.of("STAFF"))
                    .build();

    @BeforeEach
    void seed() {
        NOW.set(Instant.parse("2026-11-01T02:15:00Z"));
        jdbc.execute("TRUNCATE booking_staff_commands,bookings CASCADE");
        reset(dependencies, authority);
        when(dependencies.courts(eq(7L), any()))
                .thenReturn(
                        List.of(
                                new CounterDependencies.Court(21, 7, "Court 1", "ACTIVE"),
                                new CounterDependencies.Court(22, 7, "Empty court", "ACTIVE")));
        when(dependencies.canonicalSlots(eq(21L), any()))
                .thenAnswer(invocation -> dependencies.slots(21, invocation.getArgument(1)));
        when(dependencies.slots(eq(21L), any()))
                .thenReturn(
                        List.of(
                                slot(1, "09:00", "09:30", 100000),
                                slot(2, "09:30", "10:00", 150000),
                                slot(3, "10:00", "10:30", 200000)));
    }

    CounterDependencies.Slot slot(long id, String start, String end, long price) {
        return new CounterDependencies.Slot(
                id,
                LocalTime.parse(start),
                LocalTime.parse(end),
                BigDecimal.valueOf(price),
                "AVAILABLE");
    }

    StaffCounter.WalkIn request() {
        return new StaffCounter.WalkIn(
                7,
                100,
                21,
                DAY,
                List.of(1L),
                "0912345678",
                "CASH",
                NOW.get(),
                new StaffCounter.Quote(
                        new BigDecimal("100000"),
                        BigDecimal.ZERO,
                        new BigDecimal("100000"),
                        "counter-v1",
                        List.of(
                                new StaffCounter.Item(
                                        1,
                                        LocalTime.of(9, 0),
                                        LocalTime.of(9, 30),
                                        new BigDecimal("100000")))),
                "Walk-in guest");
    }

    String key(String kind) {
        return "staff:" + kind + ":100:" + UUID.randomUUID();
    }

    @Test
    void walkInPersistsContactCashSnapshotAndImmutableReplay() {
        String key = key("walk-in");
        var first = counter.create(jwt, key, SECRET, request());
        assertThat(counter.create(jwt, key, SECRET, request())).isEqualTo(first);
        assertThat(counter.reconcile(jwt, key, SECRET, request())).isEqualTo(first);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT customer_name FROM bookings WHERE id=?",
                                String.class,
                                first.bookingId()))
                .isEqualTo("Walk-in guest");
        verify(dependencies, times(1))
                .reserve(eq(7L), eq(100L), eq(21L), eq(DAY), eq(List.of(1L)), eq(key), any());
        var other = request();
        other =
                new StaffCounter.WalkIn(
                        7,
                        100,
                        21,
                        DAY,
                        List.of(1L),
                        "0912345678",
                        "CASH",
                        NOW.get(),
                        other.quote(),
                        "Changed contact");
        var changed = other;
        assertThatThrownBy(() -> counter.reconcile(jwt, key, SECRET, changed))
                .isInstanceOf(ResponseStatusException.class);
        var schedule = counter.schedule(jwt, 7, DAY, DAY.plusDays(6));
        assertThat(schedule.courts()).hasSize(2);
        assertThat(schedule.courts().get(1).slots()).isEmpty();
        assertThat(counter.busy(7, 21, DAY, List.of(1L, 2L), jwt)).containsExactly(1L);
        assertThat(counter.occupancy(21, DAY).bookedSlotIds()).containsExactly(1L);
    }

    @Test
    void overtimeUsesEachStartedHalfHourRateAndRejectsStaleConsent() {
        var booking = counter.create(jwt, key("walk-in"), SECRET, request());
        NOW.set(Instant.parse("2026-11-01T03:01:00Z"));
        var quote = counter.preview(jwt, booking.bookingId(), 7, 100);
        assertThat(quote.surchargeAmount()).isEqualByComparingTo("350000");
        assertThatThrownBy(
                        () ->
                                counter.checkout(
                                        jwt,
                                        booking.bookingId(),
                                        key("checkout"),
                                        SECRET,
                                        new StaffCounter.Checkout(
                                                7,
                                                100,
                                                NOW.get(),
                                                new BigDecimal("150000"),
                                                new BigDecimal("250000"))))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM bookings WHERE id=?",
                                String.class,
                                booking.bookingId()))
                .isEqualTo("CHECKED_IN");
        String key = key("checkout");
        var command =
                new StaffCounter.Checkout(
                        7, 100, NOW.get(), new BigDecimal("350000"), new BigDecimal("450000"));
        var result = counter.checkout(jwt, booking.bookingId(), key, SECRET, command);
        assertThat(result.totalAmount()).isEqualByComparingTo("450000");
        assertThat(counter.checkout(jwt, booking.bookingId(), key, SECRET, command))
                .isEqualTo(result);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM booking_cash_settlements", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void fractionalOvertimeCountsNextSlotAndOnTimeCountsZero() {
        var booking = counter.create(jwt, key("walk-in"), SECRET, request());
        NOW.set(Instant.parse("2026-11-01T02:30:00Z"));
        assertThat(counter.preview(jwt, booking.bookingId(), 7, 100).surchargeAmount())
                .isEqualByComparingTo("0");
        NOW.set(NOW.get().plusNanos(1));
        assertThat(counter.preview(jwt, booking.bookingId(), 7, 100).surchargeAmount())
                .isEqualByComparingTo("150000");
    }

    @Test
    void missingRatesOrServiceCredentialCannotCompleteOrCreate() {
        assertThatThrownBy(() -> counter.create(jwt, key("walk-in"), "wrong", request()))
                .isInstanceOf(ResponseStatusException.class);
        var booking = counter.create(jwt, key("walk-in"), SECRET, request());
        NOW.set(Instant.parse("2026-11-01T03:31:00Z"));
        assertThatThrownBy(() -> counter.preview(jwt, booking.bookingId(), 7, 100))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM bookings WHERE id=?",
                                String.class,
                                booking.bookingId()))
                .isEqualTo("CHECKED_IN");
    }

    @Test
    void cancelledSlotsAreReleasedButRetainHistory() {
        var first = counter.create(jwt, key("walk-in"), SECRET, request());
        jdbc.update("UPDATE bookings SET status='CANCELLED' WHERE id=?", first.bookingId());
        var second = counter.create(jwt, key("walk-in"), SECRET, request());
        assertThat(second.bookingId()).isNotEqualTo(first.bookingId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_slots", Integer.class))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM booking_slots WHERE active", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentWalkInsCannotClaimSameCourtSlot() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<Boolean>>();
            for (int n = 0; n < 2; n++)
                futures.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        counter.create(jwt, key("walk-in"), SECRET, request());
                                        return true;
                                    } catch (ResponseStatusException e) {
                                        return false;
                                    }
                                }));
            gate.countDown();
            int successes = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings", Integer.class))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void posChargeReplayAndCheckoutShareOneLockedTotal() {
        var booking=counter.create(jwt,key("walk-in"),SECRET,request());
        String code="POS-"+UUID.randomUUID();
        var item=new BookingPosCharges.Item(1,"Water","bottle",2,new BigDecimal("10000.00"),new BigDecimal("20000.00"));
        var charge=new BookingPosCharges.Request(code,7,100,new BigDecimal("20000.00"),List.of(item));
        String bearer="Bearer fixture-pos-booking-secret-not-production";
        assertThatThrownBy(()->charges.attach("Bearer wrong",booking.bookingId(),code,charge)).isInstanceOf(ResponseStatusException.class);
        assertThat(charges.attach(bearer,booking.bookingId(),code,charge).status()).isEqualTo("ACCEPTED");
        charges.attach(bearer,booking.bookingId(),code,charge);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_pos_charges",Integer.class)).isEqualTo(1);
        var preview=counter.preview(jwt,booking.bookingId(),7,100);
        assertThat(preview.posAmount()).isEqualByComparingTo("20000");
        assertThat(preview.totalAmount()).isEqualByComparingTo("120000");
        assertThatThrownBy(()->counter.checkout(jwt,booking.bookingId(),key("checkout"),SECRET,new StaffCounter.Checkout(7,100,NOW.get(),BigDecimal.ZERO,new BigDecimal("100000")))).isInstanceOf(ResponseStatusException.class);
        counter.checkout(jwt,booking.bookingId(),key("checkout"),SECRET,new StaffCounter.Checkout(7,100,NOW.get(),BigDecimal.ZERO,new BigDecimal("120000")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_pos_settlement_outbox",Integer.class)).isEqualTo(1);
        charges.attach(bearer,booking.bookingId(),code,charge);
        String other="POS-"+UUID.randomUUID();
        assertThatThrownBy(()->charges.attach(bearer,booking.bookingId(),other,new BookingPosCharges.Request(other,7,100,new BigDecimal("20000.00"),List.of(item)))).isInstanceOf(ResponseStatusException.class);
    }
}
