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
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
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
                    booking.totalPrice(),"VCB","970436","00123","FCOURT","FC BK00000042",booking.holdExpiresAt(), "Khách hàng");
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

    @Autowired org.springframework.test.web.servlet.MockMvc securedMvc;
    @Autowired PaymentApprovalService approvals;
    @org.springframework.test.context.bean.override.mockito.MockitoBean BranchOwnership ownership;
    org.springframework.security.oauth2.jwt.Jwt admin() {
        return org.springframework.security.oauth2.jwt.Jwt.withTokenValue("token").header("alg","RS256").subject("9").claim("roles",java.util.List.of("ADMIN")).build();
    }
    void ownBranch() {
        org.mockito.Mockito.when(ownership.actor(org.mockito.ArgumentMatchers.any())).thenReturn(9L);
    }
    PaymentRecord submitted() throws Exception {
        return receipts(fixed(), b -> "https://res.cloudinary.com/demo/bill.png").upload(42,null,"guest",image());
    }
    @Test
    void adminRoutesRejectMissingBadTokenAndCustomerRole() throws Exception {
        var get=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/payments/pending?branchId=7");
        securedMvc.perform(get).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        securedMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/payments/pending?branchId=7").header("Authorization","Bearer tampered"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        securedMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/payments/pending?branchId=7")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CUSTOMER"))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(ownership);
    }
    @Test
    void confirmIsIdempotentAndRejectCannotOverwriteConfirmation() throws Exception {
        var p=submitted(); ownBranch();
        assertThat(approvals.pending(7,0,admin())).hasSize(1);
        var saved=approvals.decide(p.id(),true,null,admin());
        assertThat(saved.status()).isEqualTo("CONFIRMED");
        assertThat(approvals.decide(p.id(),true,null,admin()).id()).isEqualTo(p.id());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> approvals.decide(p.id(),false,"wrong amount",admin())).hasMessageContaining("409");
        assertThat(approvals.pending(7,0,admin())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT confirmed_by FROM payments",Long.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox",Long.class)).isEqualTo(2);
    }
    @Test
    void foreignBranchAndMissingReasonCannotChangePayment() throws Exception {
        var p=submitted();
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN))
            .when(ownership).require(org.mockito.ArgumentMatchers.eq(7L),org.mockito.ArgumentMatchers.any());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> approvals.decide(p.id(),true,null,admin())).hasMessageContaining("403");
        org.mockito.Mockito.reset(ownership); ownBranch();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> approvals.decide(p.id(),false," ",admin())).hasMessageContaining("400");
        assertThat(store.find(42).status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox",Long.class)).isEqualTo(1);
    }
    @Test
    void rejectPersistsReasonAndEmitsCancellationContract() throws Exception {
        var p=submitted();ownBranch();
        var rejected=approvals.decide(p.id(),false,"  Sai số tiền  ",admin());
        assertThat(rejected.status()).isEqualTo("REJECTED");assertThat(rejected.rejectionReason()).isEqualTo("Sai số tiền");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox WHERE event_type='PaymentRejectedEvent'",Long.class)).isEqualTo(1);
    }
    @Test
    void parallelConfirmAndRejectOnlyOneDecisionWins() throws Exception {
        var p=submitted();ownBranch();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks=java.util.List.<java.util.concurrent.Callable<String>>of(
                () -> { try{return approvals.decide(p.id(),true,null,admin()).status();}catch(org.springframework.web.server.ResponseStatusException e){return "conflict";} },
                () -> { try{return approvals.decide(p.id(),false,"wrong amount",admin()).status();}catch(org.springframework.web.server.ResponseStatusException e){return "conflict";} });
            var results=pool.invokeAll(tasks);
            assertThat(java.util.List.of(results.get(0).get(),results.get(1).get())).contains("conflict");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox",Long.class)).isEqualTo(2);
    }
    @Test
    void bookingOwnershipRequiresMatchingIdAndOwnerFromFacility() {
        var builder=org.springframework.web.client.RestClient.builder().baseUrl("http://facility");
        var server=org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var guard=new BranchOwnership(builder.build());
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://facility/api/admin/branches/7"))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("Authorization","Bearer token"))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("{\"id\":7,\"adminId\":10}",org.springframework.http.MediaType.APPLICATION_JSON));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> guard.require(7,admin())).hasMessageContaining("403");
        server.verify();
    }
    @Test
    void outboxFailureRollsBackApproval() throws Exception {
        var p=submitted();ownBranch();
        jdbc.execute("CREATE FUNCTION fail_payment_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'simulated outbox failure'; END; $$");
        jdbc.execute("CREATE TRIGGER fail_event BEFORE INSERT ON payment_outbox FOR EACH ROW EXECUTE FUNCTION fail_payment_event()");
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> approvals.decide(p.id(),true,null,admin())).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(store.find(42).status()).isEqualTo("PENDING");
        } finally {
            jdbc.execute("DROP TRIGGER fail_event ON payment_outbox");jdbc.execute("DROP FUNCTION fail_payment_event()");
        }
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
