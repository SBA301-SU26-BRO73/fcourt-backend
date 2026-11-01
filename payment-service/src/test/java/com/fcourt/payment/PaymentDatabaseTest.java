package com.fcourt.payment;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties="payment.events-enabled=false")
class PaymentDatabaseTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payment_db");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach
    void clear() { jdbc.execute("TRUNCATE payment_outbox,payments RESTART IDENTITY"); }

    @Autowired PaymentStore store;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;

    ReceiptService receipts(java.time.Clock clock, ReceiptStorage storage) {
        var upstream = org.mockito.Mockito.mock(PaymentUpstreams.class);
        var checkout = org.mockito.Mockito.mock(CheckoutService.class);
        try {
            var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            var booking = json.readValue(CheckoutApiTest.BOOKING, PaymentUpstreams.Booking.class);
            org.mockito.Mockito.when(upstream.booking(org.mockito.ArgumentMatchers.eq(42L),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq("guest"))).thenReturn(booking);
            var info = new CheckoutInfo(42L,"BK00000042",7L,"Thu Duc","Court 1",booking.date(),booking.slots(),
                    booking.totalPrice(),"VCB","970436","00123","FCOURT","FC BK00000042",booking.holdExpiresAt());
            org.mockito.Mockito.when(checkout.checkout(42L,null,"guest")).thenReturn(info);
        } catch (Exception e) { throw new RuntimeException(e); }
        return new ReceiptService(checkout,upstream,store,storage,tx,clock);
    }
    org.springframework.mock.web.MockMultipartFile image() throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4,4,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);
        return new org.springframework.mock.web.MockMultipartFile("file","bill.png","image/png",out.toByteArray());
    }
    java.time.Clock fixed() { return java.time.Clock.fixed(CheckoutApiTest.NOW,java.time.ZoneOffset.UTC); }

    @Test
    void receiptAndOutboxPersistAtomicallyAndRetryDoesNotUploadAgain() throws Exception {
        var storage=org.mockito.Mockito.mock(ReceiptStorage.class);
        org.mockito.Mockito.when(storage.upload(org.mockito.ArgumentMatchers.any())).thenReturn("https://res.cloudinary.com/demo/bill.png");
        var service=receipts(fixed(),storage);
        var first=service.upload(42,null,"guest",image());
        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(first.receiptImageUrl()).startsWith("https://res.cloudinary.com/");
        assertThat(service.upload(42,null,"guest",image()).id()).isEqualTo(first.id());
        assertThat(service.status(42,null,"guest").amount()).isEqualByComparingTo("160000");
        org.mockito.Mockito.verify(storage,org.mockito.Mockito.times(1)).upload(org.mockito.ArgumentMatchers.any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT event_type FROM payment_outbox",String.class)).isEqualTo("PaymentSubmittedEvent");
    }
    @Test
    void spoofedImagesAndOversizedFilesFailBeforeStorage() {
        var storage=org.mockito.Mockito.mock(ReceiptStorage.class);
        var service=receipts(fixed(),storage);
        var bad=new org.springframework.mock.web.MockMultipartFile("file","bill.png","image/png","not-an-image".getBytes());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.upload(42,null,"guest",bad))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("400");
        var big=new org.springframework.mock.web.MockMultipartFile("file","bill.jpg","image/jpeg",new byte[ReceiptValidator.MAX_BYTES+1]);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.upload(42,null,"guest",big)).hasMessageContaining("400");
        org.mockito.Mockito.verifyNoInteractions(storage);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments",Long.class)).isZero();
    }
    @Test
    void expiryInFlightCannotSubmitOrEmitEvent() throws Exception {
        var service=receipts(java.time.Clock.fixed(CheckoutApiTest.NOW.plusSeconds(300),java.time.ZoneOffset.UTC), b -> "https://res.cloudinary.com/demo/bill.png");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.upload(42,null,"guest",image())).hasMessageContaining("410");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments",Long.class)).isZero();
    }
    @Test
    void unauthorizedMultipartAndStatusReturn401() throws Exception {
        var service=receipts(fixed(), b -> "unused");
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ReceiptController(service)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/payments/42/upload-receipt").file(image()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments/42/status"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }
    @Test
    void competingUploadsCreateExactlyOnePaymentAndSubmissionEvent() throws Exception {
        var service=receipts(fixed(), b -> "https://res.cloudinary.com/demo/bill.png");
        var file=image();
        try (var pool=java.util.concurrent.Executors.newFixedThreadPool(6)) {
            var calls=new java.util.ArrayList<java.util.concurrent.Callable<Long>>();
            for(int i=0;i<6;i++) calls.add(() -> service.upload(42,null,"guest",file).id());
            var results=pool.invokeAll(calls);
            long id=results.getFirst().get();
            for(var result:results) assertThat(result.get()).isEqualTo(id);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox",Long.class)).isEqualTo(1);
    }
    @Test
    void relayRetriesFailureAndMarksOnlyAcknowledgedEvents() throws Exception {
        receipts(fixed(),b -> "https://res.cloudinary.com/demo/bill.png").upload(42,null,"guest",image());
        @SuppressWarnings("unchecked") var kafka=(org.springframework.kafka.core.KafkaTemplate<String,String>)org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new RuntimeException("broker down")))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        var relay=new OutboxRelay(store,kafka,tx);
        relay.publish();
        assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM payment_outbox",Boolean.class)).isTrue();
        relay.publish();
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM payment_outbox",Boolean.class)).isTrue();
    }

    @Test
    void migrationsInitializeIsolatedPaymentDatabaseWithExactDecimalAmount() {
        jdbc.update("INSERT INTO payments(branch_id,amount) VALUES (?,?)", 7L, new java.math.BigDecimal("160000.00"));
        assertThat(jdbc.queryForObject("SELECT amount FROM payments", java.math.BigDecimal.class))
                .isEqualByComparingTo("160000.00");
        assertThat(jdbc.queryForObject("SELECT status FROM payments", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_requests", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Long.class)).isEqualTo(2);
    }
}
