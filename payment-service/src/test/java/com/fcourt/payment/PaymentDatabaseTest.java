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
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties="payment.events-enabled=false")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class PaymentDatabaseTest {
    @Test void receiptPriceConflictRollsBackPaymentAndCleansUploadedBill() throws Exception {
        var upstream=org.mockito.Mockito.mock(PaymentUpstreams.class);
        var checkout=org.mockito.Mockito.mock(CheckoutService.class);
        var storage=org.mockito.Mockito.mock(ReceiptStorage.class);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var order=mapper.readValue(CheckoutApiTest.BOOKING,PaymentUpstreams.Booking.class);
        org.mockito.Mockito.when(upstream.booking(42,null,"guest")).thenReturn(order);
        var info=new CheckoutInfo(42L,"BK00000042",7L,"Thu Duc","Court",order.date(),order.slots(),order.totalPrice(),
                "VCB","970436","00123","FCOURT","FC BK00000042",order.holdExpiresAt(),"Customer");
        org.mockito.Mockito.when(checkout.checkout(42,null,"guest")).thenReturn(info);
        org.mockito.Mockito.when(storage.upload(org.mockito.ArgumentMatchers.any())).thenReturn("https://res.cloudinary.com/demo/new-bill.png");
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT))
                .when(upstream).freeze(42,null,"guest",info.amount());
        var receipts=new ReceiptService(checkout,upstream,store,storage,tx,fixed());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->receipts.upload(42,null,"guest",image())).hasMessageContaining("409");
        assertThat(store.find(42)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox",Long.class)).isZero();
        org.mockito.Mockito.verify(storage).delete("https://res.cloudinary.com/demo/new-bill.png");
    }

    @Test void freeVoucherPaymentIssuesTicketAndConfirmationExactlyOnce() throws Exception {
        var upstream=org.mockito.Mockito.mock(PaymentUpstreams.class);
        var checkout=org.mockito.Mockito.mock(CheckoutService.class);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        String body=CheckoutApiTest.BOOKING.replace("160000.00","0").replace("\"status\":",
                "\"voucherApplicationId\":\"00000000-0000-0000-0000-000000000001\",\"status\":");
        var order=mapper.readValue(body,PaymentUpstreams.Booking.class);
        org.mockito.Mockito.when(upstream.booking(42,"Bearer free-user",null)).thenReturn(order);
        var info=new CheckoutInfo(42L,"BK00000042",7L,"Thu Duc","Court",order.date(),order.slots(),java.math.BigDecimal.ZERO,
                "VCB","970436","00123","FCOURT","FC BK00000042",order.holdExpiresAt(),"Customer");
        org.mockito.Mockito.when(checkout.checkout(42,"Bearer free-user",null)).thenReturn(info);
        var free=new FreeVoucherPayment(upstream,checkout,store,tx,fixed());
        var customer=org.springframework.security.oauth2.jwt.Jwt.withTokenValue("free-user").header("alg","RS256").subject("10").build();
        var first=free.confirm(42,customer);var retry=free.confirm(42,customer);
        assertThat(first.amount()).isZero();assertThat(first.status()).isEqualTo("CONFIRMED");
        assertThat(first.checkInCode()).matches("[0-9]{32}");assertThat(retry.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("SELECT customer_id FROM payments WHERE booking_id=42",Long.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox WHERE event_type='PaymentConfirmedEvent'",Long.class)).isEqualTo(1);
        org.mockito.Mockito.verify(upstream,org.mockito.Mockito.times(1)).freeze(42,"Bearer free-user",null,java.math.BigDecimal.ZERO);
    }

    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payment_db");

    @Container static final org.testcontainers.containers.GenericContainer<?> REDIS=new org.testcontainers.containers.GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host",REDIS::getHost);
        registry.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach
    void clear() {
        jdbc.execute("TRUNCATE walk_in_cash_ledger,refund_requests,payment_customer_sync,payment_outbox,payments RESTART IDENTITY");
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>)c->{c.serverCommands().flushDb();return null;});
    }
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;

    @Autowired PaymentStore store;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;

    ReceiptService receipts(java.time.Clock clock, ReceiptStorage storage) {
        return receipts(clock,storage,"PENDING_PAYMENT");
    }
    ReceiptService receipts(java.time.Clock clock,ReceiptStorage storage,String state) {
        var upstream = org.mockito.Mockito.mock(PaymentUpstreams.class);
        var checkout = org.mockito.Mockito.mock(CheckoutService.class);
        try {
            var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            var booking = json.readValue(CheckoutApiTest.BOOKING.replace("PENDING_PAYMENT",state), PaymentUpstreams.Booking.class);
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
    void bookingExpiryNeverDiscardsAnAcceptedReceiptAndRequiresCredential() throws Exception {
        String secret="fixture-payment-booking-secret-not-production";
        var now=CheckoutApiTest.NOW.plusSeconds(3600);
        var controller=new BookingExpiryController(store,tx,java.time.Clock.fixed(now,java.time.ZoneOffset.UTC),secret);
        var request=new BookingExpiryController.Request(42,now.minusSeconds(1));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.decide(null,request))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(controller.decide(secret,request).getBody().mayExpire()).isTrue();
        var storage=org.mockito.Mockito.mock(ReceiptStorage.class);
        org.mockito.Mockito.when(storage.upload(org.mockito.ArgumentMatchers.any())).thenReturn("https://res.cloudinary.com/fixture/bill.png");
        receipts(fixed(),storage).upload(42,null,"guest",image());
        assertThat(controller.decide(secret,request).getBody().mayExpire()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.decide(secret,new BookingExpiryController.Request(42,now.plusSeconds(1))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void bookingExpiryWaitsForConcurrentReceiptCommit() throws Exception {
        String secret="fixture-payment-booking-secret-not-production";
        var now=CheckoutApiTest.NOW.plusSeconds(3600);
        var controller=new BookingExpiryController(store,tx,java.time.Clock.fixed(now,java.time.ZoneOffset.UTC),secret);
        var locked=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var upload=pool.submit(() -> tx.executeWithoutResult(ignored -> {
                jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)",Object.class,42L);locked.countDown();
                try { release.await(); } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                jdbc.update("INSERT INTO payments(branch_id,amount,status,booking_id,submitted_at) VALUES (7,100000,'PENDING',42,now())");
            }));
            locked.await();
            var decision=pool.submit(() -> controller.decide(secret,new BookingExpiryController.Request(42,now.minusSeconds(1))));
            try { org.assertj.core.api.Assertions.assertThatThrownBy(() -> decision.get(100,java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class); }
            finally { release.countDown(); }
            upload.get();assertThat(decision.get().getBody().mayExpire()).isFalse();
        }
    }

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
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ReceiptController(service,org.mockito.Mockito.mock(CustomerPayments.class))).build();
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

    @Test void confirmationTimestampMatchesStoredPaymentAndPublishedEvent() throws Exception {
        var payment=submitted();ownBranch();var confirmed=approvals.decide(payment.id(),true,null,admin());
        var stored=jdbc.queryForObject("SELECT confirmed_at FROM payments WHERE id=?",java.sql.Timestamp.class,payment.id()).toInstant();
        assertThat(confirmed.confirmedAt()).isEqualTo(stored);
        var event=new com.fasterxml.jackson.databind.ObjectMapper().readTree(jdbc.queryForObject("SELECT payload::text FROM payment_outbox WHERE event_type='PaymentConfirmedEvent'",String.class));
        assertThat(java.time.Instant.parse(event.path("payment").path("confirmedAt").asText())).isEqualTo(stored);
        assertThat(approvals.decide(payment.id(),true,null,admin()).confirmedAt()).isEqualTo(stored);
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

    CheckoutInfo checkout() throws Exception {
        var booking=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().readValue(CheckoutApiTest.BOOKING,PaymentUpstreams.Booking.class);
        return new CheckoutInfo(42L,"BK00000042",7L,"Thu Duc","Court 1",booking.date(),booking.slots(),booking.totalPrice(),
                "VCB","970436","00123","FCOURT","FC BK00000042",booking.holdExpiresAt(),"Khách hàng");
    }
    PaymentSessions sessions(java.time.Clock clock) {return new PaymentSessions(store,tx,clock);}
    @Test
    void checkoutTracksExpiryAndSweepCancelsOnlyUnsubmittedSessions() throws Exception {
        sessions(fixed()).track(checkout());
        sessions(fixed()).track(checkout());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments",Long.class)).isEqualTo(1);
        sessions(java.time.Clock.fixed(CheckoutApiTest.NOW.plusSeconds(299),java.time.ZoneOffset.UTC)).expire();
        assertThat(store.find(42).status()).isEqualTo("PENDING");
        var expired=sessions(java.time.Clock.fixed(CheckoutApiTest.NOW.plusSeconds(300),java.time.ZoneOffset.UTC));
        expired.expire();expired.expire();
        assertThat(store.find(42).status()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox WHERE event_type='BookingCancelledEvent'",Long.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> receipts(fixed(),b->"unused").upload(42,null,"guest",image())).hasMessageContaining("410");
    }
    @Test
    void uploadedReceiptIsNeverAutoCancelledAfterFiveMinutes() throws Exception {
        sessions(fixed()).track(checkout());submitted();
        sessions(java.time.Clock.fixed(CheckoutApiTest.NOW.plusSeconds(1000),java.time.ZoneOffset.UTC)).expire();
        assertThat(store.find(42).status()).isEqualTo("PENDING");
        assertThat(store.find(42).submittedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox",Long.class)).isEqualTo(1);
    }
    @Test
    void confirmationCreatesStableUniqueCodeAndPngDecodesExactly() throws Exception {
        var p=submitted();ownBranch();
        var confirmed=approvals.decide(p.id(),true,null,admin());
        assertThat(confirmed.checkInCode()).matches("[0-9]{32}");
        assertThat(approvals.decide(p.id(),true,null,admin()).checkInCode()).isEqualTo(confirmed.checkInCode());
        assertThat(TicketQr.newCode()).isNotEqualTo(confirmed.checkInCode());
        var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(TicketQr.png(confirmed.checkInCode())));
        assertThat(image.getWidth()).isEqualTo(600);
        var source=new com.google.zxing.client.j2se.BufferedImageLuminanceSource(image);
        var decoded=new com.google.zxing.MultiFormatReader().decode(new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(source)));
        assertThat(decoded.getText()).isEqualTo(confirmed.checkInCode());
        var payload=jdbc.queryForObject("SELECT payload::text FROM payment_outbox WHERE event_type='PaymentConfirmedEvent'",String.class);
        assertThat(payload).contains(confirmed.checkInCode(),"/api/payments/42/ticket/qr");
    }
    @Test
    void ticketRequiresPaymentAndAuthoritativeBookingConfirmationAndOwner() throws Exception {
        submitted();
        var customer=receipts(fixed(),b->"unused","CONFIRMED");
        var controller=new TicketController(customer);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments/42/ticket").header("X-Hold-Token","guest"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        ownBranch();approvals.decide(store.find(42).id(),true,null,admin());
        var notSynced=new TicketController(receipts(fixed(),b->"unused"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> notSynced.ticket(42,null,"guest")).hasMessageContaining("409");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments/42/ticket"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments/42/ticket").header("X-Hold-Token","guest"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.checkInCode").value(store.find(42).checkInCode()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments/42/ticket/qr").header("X-Hold-Token","guest"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("image/png"));
    }
    @Test
    void uploadRacingExpiryCannotCancelAnAcceptedReceipt() throws Exception {
        sessions(fixed()).track(checkout());
        var service=receipts(fixed(),b->"https://res.cloudinary.com/demo/bill.png");
        var expired=sessions(java.time.Clock.fixed(CheckoutApiTest.NOW.plusSeconds(300),java.time.ZoneOffset.UTC));
        var file=image();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results=pool.invokeAll(java.util.List.<java.util.concurrent.Callable<String>>of(
                () -> {try {return service.upload(42,null,"guest",file).status();}catch(org.springframework.web.server.ResponseStatusException e){return "expired";}},
                () -> {expired.expireOne(42);return "swept";}));
            for(var result:results) result.get();
        }
        var record=store.find(42);
        if (record.submittedAt()!=null) {
            assertThat(record.status()).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox WHERE event_type='BookingCancelledEvent'",Long.class)).isZero();
        } else {
            assertThat(record.status()).isEqualTo("CANCELLED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox WHERE event_type='PaymentSubmittedEvent'",Long.class)).isZero();
        }
    }

    @Test
    void migrationsInitializeIsolatedPaymentDatabaseWithExactDecimalAmount() {
        jdbc.update("INSERT INTO payments(branch_id,amount) VALUES (?,?)", 7L, new java.math.BigDecimal("160000.00"));
        assertThat(jdbc.queryForObject("SELECT amount FROM payments", java.math.BigDecimal.class))
                .isEqualByComparingTo("160000.00");
        assertThat(jdbc.queryForObject("SELECT status FROM payments", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_requests", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Long.class)).isEqualTo(8);
    }
    @Autowired CustomerPayments customerPayments;
    @Test
    void customerHistoryRejectsSpoofedHeadersAndOtherOwners() throws Exception {
        submitted(); CustomerPayments.claim(jdbc,42,10L);
        jdbc.update("INSERT INTO payment_customer_sync(customer_id) VALUES (10),(20)");
        securedMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments/my-transactions").header("X-User-Id","10"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        securedMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments/my-transactions")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt().jwt(j->j.subject("10"))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].bookingId").value(42))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].amount").value(160000))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].status").value("PENDING"));
        securedMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments/my-transactions")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt().jwt(j->j.subject("20"))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.total").value(0));
    }
    @Test
    void customerHistoryMapsExactAmountAndEmptyPages() throws Exception {
        submitted();CustomerPayments.claim(jdbc,42,9L);jdbc.update("INSERT INTO payment_customer_sync(customer_id) VALUES (9)");
        jdbc.update("UPDATE payments SET status='CONFIRMED' WHERE booking_id=42");
        var history=customerPayments.history(admin(),0,1);
        assertThat(history.items().getFirst().amount()).isEqualByComparingTo("160000");
        assertThat(history.items().getFirst().status()).isEqualTo("SUCCESS");
        assertThat(history.items().getFirst().method()).isEqualTo("VIETQR");
        assertThat(customerPayments.history(admin(),1,1).items()).isEmpty();
        assertThat(customerPayments.history(admin(),1,1).total()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->customerPayments.history(admin(),-1,20)).hasMessageContaining("400");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->CustomerPayments.claim(jdbc,42,20L)).hasMessageContaining("409");
    }
    @Test
    void legacyHistoryReconcilesOnlyAuthoritativeOwnerBookings() throws Exception {
        submitted();
        var builder=org.springframework.web.client.RestClient.builder().baseUrl("http://booking");
        var server=org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://booking/api/bookings/my?status=ALL&page=0&size=100"))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("Authorization","Bearer token"))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("{\"items\":[{\"id\":42}],\"page\":0,\"size\":100,\"total\":1}",org.springframework.http.MediaType.APPLICATION_JSON));
        var service=new CustomerPayments(jdbc,org.mockito.Mockito.mock(org.springframework.security.oauth2.jwt.JwtDecoder.class),builder.build(),tx);
        assertThat(service.history(admin(),0,20).items()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT customer_id FROM payments WHERE booking_id=42",Long.class)).isEqualTo(9);
        service.history(admin(),0,20);server.verify(); // No second upstream read once legacy migration succeeds.
    }
    @Test
    void unavailableLegacyAuthorityNeverReturnsFalseEmptyHistory() {
        var builder=org.springframework.web.client.RestClient.builder().baseUrl("http://booking");
        var server=org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything()).andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withServerError());
        var service=new CustomerPayments(jdbc,org.mockito.Mockito.mock(org.springframework.security.oauth2.jwt.JwtDecoder.class),builder.build(),tx);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.history(admin(),0,20)).hasMessageContaining("503");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_customer_sync",Long.class)).isZero();
    }

    @Test
    void guestBookingPhoneFromBookingServiceLandsOnThePaymentAndFeedsTheLedgerFilter() throws Exception {
        receipts(fixed(),b -> "https://res.cloudinary.com/demo/bill.png","PENDING_PAYMENT\",\"customerPhone\":\"0901234567").upload(42,null,"guest",image());
        assertThat(jdbc.queryForObject("SELECT customer_phone FROM payments WHERE booking_id=42",String.class)).isEqualTo("0901234567");
        ownBranch();
        assertThat(ledger.history(7,null,null,"ALL","090123",0,20,admin()).total()).isEqualTo(1);
    }

    @Test
    void missingOrMalformedBookingPhoneLeavesCustomerPhoneEmpty() throws Exception {
        submitted();
        assertThat(jdbc.queryForObject("SELECT customer_phone FROM payments WHERE booking_id=42",String.class)).isNull();
    }

    @Autowired BranchLedger ledger;
    void ledgerSeed() throws Exception {
        submitted();
        jdbc.update("UPDATE payments SET status='CONFIRMED',customer_phone='0901234567',submitted_at='2026-11-01T00:00:00Z' WHERE booking_id=42");
        for(int i=1;i<=3;i++)jdbc.update("INSERT INTO payments(branch_id,amount,booking_id,checkout,submitted_at,status) SELECT ?,?,booking_id+?,checkout,'2026-11-01T01:00:00Z'::timestamptz,? FROM payments WHERE booking_id=42",i==3?8:7,100000*i,i,i==1?"PENDING":"REJECTED");
    }
    @Test
    void branchLedgerTotalsCoverAllFilteredRowsAndIgnoreOtherBranches() throws Exception {
        ledgerSeed();
        var page=ledger.history(7,null,null,"ALL","",0,1,admin());
        assertThat(page.total()).isEqualTo(3);assertThat(page.items()).hasSize(1);
        assertThat(page.summary().received()).isEqualByComparingTo("160000");
        assertThat(page.summary().pending()).isEqualByComparingTo("100000");
        assertThat(page.summary().rejectedCount()).isEqualTo(1);
        assertThat(ledger.history(7,null,null,"SUCCESS","090123",0,20,admin()).total()).isEqualTo(1);
        assertThat(ledger.history(7,null,null,"ALL","PAY-",0,20,admin()).total()).isEqualTo(3);
        assertThat(ledger.history(7,null,null,"ALL","%",0,20,admin()).total()).isZero();
    }
    @Test
    void branchLedgerHonorsVietnamDateRangeAndChecksOwnership() throws Exception {
        ledgerSeed();
        var day=java.time.LocalDate.of(2026,11,1);
        assertThat(ledger.history(7,day,day,"ALL","",0,20,admin()).total()).isEqualTo(3);
        assertThat(ledger.history(7,day.minusDays(1),day.minusDays(1),"ALL","",0,20,admin()).total()).isZero();
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)).when(ownership).require(org.mockito.ArgumentMatchers.eq(8L),org.mockito.ArgumentMatchers.any());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->ledger.history(8,null,null,"ALL","",0,20,admin())).hasMessageContaining("403");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->ledger.history(7,day,day.minusDays(1),"ALL","",0,20,admin())).hasMessageContaining("400");
    }
    @Test
    void branchLedgerRouteRejectsCustomerAndReturnsTotalHeaders() throws Exception {
        ledgerSeed();
        securedMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/payments/transactions?branchId=7")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        securedMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/payments/transactions?branchId=7&size=1")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Total-Received","160000.00"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.summary.rejectedCount").value(1));
    }

    @Autowired PaymentStatusCache statusCache;
    PaymentStatusService statusService(ReceiptService authority,PaymentStatusCache cache){return new PaymentStatusService(authority,org.mockito.Mockito.mock(CustomerPayments.class),store,cache);}
    @Test
    void compactStatusCacheReusesAuthorizedProofAndRejectsAnotherToken() throws Exception {
        submitted();
        var authority=org.mockito.Mockito.mock(ReceiptService.class);
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED))
            .when(authority).authorize(42,null,"wrong");
        var service=statusService(authority,statusCache);
        assertThat(service.status(42,null,"proof").paymentStatus()).isEqualTo("PENDING");
        assertThat(service.status(42,null,"proof").bookingId()).isEqualTo(42);
        org.mockito.Mockito.verify(authority,org.mockito.Mockito.times(1)).authorize(42,null,"proof");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.status(42,null,"wrong")).hasMessageContaining("401");
        assertThat(statusCache.get(42).guestHash()).isNotEqualTo("proof");
    }
    @Test
    void approvalInvalidatesCacheAndFencesLateOldWrites() throws Exception {
        submitted();ownBranch();var authority=org.mockito.Mockito.mock(ReceiptService.class);
        var service=statusService(authority,statusCache);service.status(42,null,"proof");
        var old=statusCache.get(42);
        approvals.decide(store.find(42).id(),true,null,admin());
        assertThat(statusCache.get(42)).isNull();statusCache.put(old);
        assertThat(statusCache.get(42)).isNull();
        assertThat(service.status(42,null,"proof").paymentStatus()).isEqualTo("SUCCESS");
        org.mockito.Mockito.verify(authority,org.mockito.Mockito.times(2)).authorize(42,null,"proof");
    }
    @Test
    void redisFailureFallsBackToAuthorityAndDatabase() throws Exception {
        submitted();
        var broken=org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.mockito.Mockito.when(broken.opsForValue()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        var cache=new PaymentStatusCache(broken,new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),true);
        var authority=org.mockito.Mockito.mock(ReceiptService.class);
        assertThat(statusService(authority,cache).status(42,null,"proof").paymentStatus()).isEqualTo("PENDING");
        org.mockito.Mockito.verify(authority).authorize(42,null,"proof");
    }
    @Test
    void compactRouteReturnsOnlyMinimalContractAndLocalCacheBenchmark() throws Exception {
        submitted();
        var service=statusService(org.mockito.Mockito.mock(ReceiptService.class),statusCache);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new CompactPaymentStatusController(service)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments/42/status?compact=true").header("X-Hold-Token","proof"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.paymentStatus").value("PENDING"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.id").doesNotExist())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.receiptImageUrl").doesNotExist());
        var nanos=new java.util.ArrayList<Long>();
        for(int i=0;i<200;i++){long start=System.nanoTime();service.status(42,null,"proof");nanos.add(System.nanoTime()-start);}
        nanos.sort(Long::compareTo);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/status-cache-benchmark.json"),
            "{\"samples\":200,\"p50ms\":"+nanos.get(100)/1e6+",\"p95ms\":"+nanos.get(190)/1e6+",\"note\":\"local warm guest service+Redis, excludes HTTP/TLS/JWT/upstream and production load\"}");
    }

    RefundService refundService(String state,String amount) throws Exception {
        var authority=org.mockito.Mockito.mock(PaymentUpstreams.class);
        var booking=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().readValue(
            CheckoutApiTest.BOOKING.replace("PENDING_PAYMENT",state).replace("\"id\":42","\"refundAmount\":"+amount+",\"id\":42"),PaymentUpstreams.Booking.class);
        org.mockito.Mockito.when(authority.booking(42,"Bearer token",null)).thenReturn(booking);
        org.mockito.Mockito.when(authority.booking(42,"Bearer other",null)).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        return new RefundService(store,authority,tx);
    }
    RefundService.Request refundRequest(){return new RefundService.Request(42,"Vietcombank","970436","00123456789","NGUYEN VAN A","Đổi lịch");}
    @Test
    void refundUsesServerEntitlementAndDuplicateRequestDoesNotCreateAnother() throws Exception {
        submitted();jdbc.update("UPDATE payments SET status='CONFIRMED' WHERE booking_id=42");
        var service=refundService("CANCELLED","80000");
        var first=service.request(refundRequest(),admin());var retry=service.request(refundRequest(),admin());
        assertThat(first.amount()).isEqualByComparingTo("80000");assertThat(retry.id()).isEqualTo(first.id());
        assertThat(first.status()).isEqualTo("PENDING");assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_requests",Long.class)).isEqualTo(1);
        // One RefundRequestedEvent for the owner, none for the repeated request.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox WHERE event_type='RefundRequestedEvent'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payload->'refund'->>'branchId' FROM payment_outbox WHERE event_type='RefundRequestedEvent'",String.class)).isEqualTo("7");
        assertThat(service.state(42,admin()).request().id()).isEqualTo(first.id());
        var other=org.springframework.security.oauth2.jwt.Jwt.withTokenValue("other").header("alg","RS256").subject("20").build();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.state(42,other)).hasMessageContaining("404");
    }
    @Test
    void refundRejectsUnpaidUncancelledAndExcessiveAmount() throws Exception {
        submitted();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->refundService("CANCELLED","80000").request(refundRequest(),admin())).hasMessageContaining("409");
        jdbc.update("UPDATE payments SET status='CONFIRMED' WHERE booking_id=42");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->refundService("CONFIRMED","80000").request(refundRequest(),admin())).hasMessageContaining("409");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->refundService("CANCELLED","200000").request(refundRequest(),admin())).hasMessageContaining("409");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->refundService("CANCELLED","0").request(refundRequest(),admin())).hasMessageContaining("409");
    }
    @Test
    void concurrentRefundRequestsRemainUnique() throws Exception {
        submitted();jdbc.update("UPDATE payments SET status='CONFIRMED' WHERE booking_id=42");var service=refundService("CANCELLED","80000");
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var start=new java.util.concurrent.CountDownLatch(1);var calls=new java.util.ArrayList<java.util.concurrent.Future<RefundView>>();
            for(int n=0;n<2;n++)calls.add(pool.submit(()->{start.await();return service.request(refundRequest(),admin());}));
            start.countDown();assertThat(calls.get(0).get(10,java.util.concurrent.TimeUnit.SECONDS).id()).isEqualTo(calls.get(1).get(10,java.util.concurrent.TimeUnit.SECONDS).id());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_requests",Long.class)).isEqualTo(1);
    }
    @Test
    void refundRouteRequiresAuthAndValidVietnamBankFields() throws Exception {
        var body="{\"bookingId\":42,\"bankName\":\"VCB\",\"bankBin\":\"970436\",\"bankAccountNumber\":\"bad\",\"bankAccountName\":\"A\",\"reason\":\"test\"}";
        securedMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/refunds/request").contentType("application/json").content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        securedMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/refunds/request").contentType("application/json").content(body)
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt().jwt(j->j.subject("9"))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }

    long pendingRefund() throws Exception {
        submitted();jdbc.update("UPDATE payments SET status='CONFIRMED' WHERE booking_id=42");ownBranch();
        return refundService("CANCELLED","80000").request(refundRequest(),admin()).id();
    }
    AdminRefundService adminRefunds(ReceiptStorage storage){return new AdminRefundService(store,ownership,storage,tx);}
    @Test
    void refundCompletionRequiresActualImageAndCannotOverwriteCompletedProof() throws Exception {
        long id=pendingRefund();var storage=org.mockito.Mockito.mock(ReceiptStorage.class);
        org.mockito.Mockito.when(storage.uploadRefundProof(org.mockito.ArgumentMatchers.any())).thenReturn("refund-proofs/proof.png");
        var service=adminRefunds(storage);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.decide(id,"COMPLETED",null,null,admin())).hasMessageContaining("400");
        var bad=new org.springframework.mock.web.MockMultipartFile("file","fake.jpg","image/jpeg","fake".getBytes());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.decide(id,"COMPLETED",null,bad,admin())).hasMessageContaining("400");
        org.mockito.Mockito.verifyNoInteractions(storage);
        assertThat(service.decide(id,"APPROVED",null,null,admin()).status()).isEqualTo("APPROVED");
        var completed=service.decide(id,"COMPLETED",null,image(),admin());
        assertThat(completed.transferProofUrl()).isEqualTo("refund-proofs/proof.png");
        assertThat(service.decide(id,"COMPLETED",null,image(),admin()).id()).isEqualTo(id);
        org.mockito.Mockito.verify(storage,org.mockito.Mockito.times(1)).uploadRefundProof(org.mockito.ArgumentMatchers.any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox WHERE event_type='RefundCompletedEvent'",Long.class)).isEqualTo(1);
        String payload=jdbc.queryForObject("SELECT payload::text FROM payment_outbox WHERE event_type='RefundCompletedEvent'",String.class);
        assertThat(payload).doesNotContain("bankAccountNumber").contains("transferProofUrl");
    }
    @Test
    void refundDecisionRaceHasOneFinalStateAndOneCompletionEvent() throws Exception {
        long id=pendingRefund();var service=adminRefunds(bytes->"refund-proofs/proof.png");
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var start=new java.util.concurrent.CountDownLatch(1);var calls=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(boolean complete:new boolean[]{true,false})calls.add(pool.submit(()->{try{start.await();service.decide(id,complete?"COMPLETED":"REJECTED","Không hợp lệ",complete?image():null,admin());}catch(org.springframework.web.server.ResponseStatusException e){assertThat(e.getStatusCode().value()).isEqualTo(409);}catch(Exception e){throw new RuntimeException(e);}}));
            start.countDown();for(var call:calls)call.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        String state=jdbc.queryForObject("SELECT status FROM refund_requests WHERE id=?",String.class,id);
        assertThat(state).isIn("COMPLETED","REJECTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox WHERE event_type='RefundCompletedEvent'",Long.class)).isEqualTo(state.equals("COMPLETED")?1:0);
    }
    @Test
    void refundOutboxFailureRollsBackCompletion() throws Exception {
        long id=pendingRefund();
        jdbc.execute("ALTER TABLE payment_outbox ADD CONSTRAINT test_refund_outbox CHECK(event_type<>'RefundCompletedEvent') NOT VALID");
        try{
            org.assertj.core.api.Assertions.assertThatThrownBy(()->adminRefunds(bytes->"refund-proofs/proof.png").decide(id,"COMPLETED",null,image(),admin())).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT status FROM refund_requests WHERE id=?",String.class,id)).isEqualTo("PENDING");
        }finally{jdbc.execute("ALTER TABLE payment_outbox DROP CONSTRAINT test_refund_outbox");}
    }
    @Test
    void refundAdminRoutesRequireRoleBranchOwnershipAndProofPart() throws Exception {
        long id=pendingRefund();
        securedMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/refunds/pending?branchId=7")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)).when(ownership).require(org.mockito.ArgumentMatchers.eq(8L),org.mockito.ArgumentMatchers.any());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->adminRefunds(bytes->"unused").pending(8,0,20,admin())).hasMessageContaining("403");
        securedMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/admin/refunds/"+id+"/complete").with(r->{r.setMethod("PUT");return r;})
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }

    @Autowired UnifiedLedger unified;
    String cashEvent(java.util.UUID eventId,String amount) {
        return "{\"eventId\":\""+eventId+"\",\"type\":\"WalkInBookingCreatedEvent\",\"version\":1,\"data\":{\"actorId\":9,\"occurredAt\":\"2026-11-01T01:00:00Z\",\"booking\":{\"bookingId\":99,\"bookingCode\":\"CASH000099\",\"branchId\":7,\"courtId\":2,\"status\":\"CHECKED_IN\",\"paymentMethod\":\"CASH\",\"amount\":"+amount+",\"checkedInAt\":\"2026-11-01T01:00:00Z\",\"invoice\":{\"receiptCode\":\"CASH-99\",\"cashierUserId\":9,\"totalAmount\":"+amount+"}}}}";
    }
    void consumeCash(String payload){tx.executeWithoutResult(s->{try{new CashLedgerConsumer(jdbc,new com.fasterxml.jackson.databind.ObjectMapper()).accept(payload);}catch(Exception e){throw new RuntimeException(e);}});}
    @Test
    void cashEventReplayIsIdempotentAndChangedAmountCannotReplaceIt() {
        var id=java.util.UUID.randomUUID();consumeCash(cashEvent(id,"50000"));consumeCash(cashEvent(id,"50000"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM walk_in_cash_ledger",Long.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->consumeCash(cashEvent(id,"60000"))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT amount FROM walk_in_cash_ledger",java.math.BigDecimal.class)).isEqualByComparingTo("50000");
    }
    @Test
    void unifiedLedgerBalancesPaymentRefundAndCashWithoutCountingPendingRefunds() throws Exception {
        long id=pendingRefund();consumeCash(cashEvent(java.util.UUID.randomUUID(),"50000"));
        assertThat(unified.history(7,null,null,"ALL","","ALL",0,1,admin()).summary().netRevenue()).isEqualByComparingTo("210000");
        adminRefunds(bytes->"refund-proofs/proof.png").decide(id,"COMPLETED",null,image(),admin());
        var result=unified.history(7,null,null,"ALL","","ALL",0,1,admin());
        assertThat(result.total()).isEqualTo(3);assertThat(result.items()).hasSize(1);
        assertThat(result.summary().received()).isEqualByComparingTo("210000");assertThat(result.summary().refunded()).isEqualByComparingTo("80000");
        assertThat(result.summary().netRevenue()).isEqualByComparingTo("130000");
        var refunds=unified.history(7,null,null,"SUCCESS","","REFUND",0,20,admin());
        assertThat(refunds.items().getFirst().amount()).isEqualByComparingTo("-80000");
        assertThat(unified.history(8,null,null,"ALL","","ALL",0,20,admin()).total()).isZero();
    }
    @Test
    void unifiedFiltersAndKeysDoNotCollideWithLegacyIds() throws Exception {
        pendingRefund();consumeCash(cashEvent(java.util.UUID.randomUUID(),"50000"));
        var page=unified.history(7,null,null,"SUCCESS","","ALL",0,20,admin());
        assertThat(page.items().stream().map(UnifiedLedger.Entry::transactionKey).distinct().count()).isEqualTo(page.items().size());
        assertThat(unified.history(7,java.time.LocalDate.of(2026,11,1),java.time.LocalDate.of(2026,11,1),"ALL","CASH-99","WALK_IN_CASH",0,20,admin()).total()).isEqualTo(1);
        assertThat(unified.history(7,null,null,"ALL","%","ALL",0,20,admin()).total()).isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->unified.history(7,null,null,"ALL","","UNKNOWN",0,20,admin())).hasMessageContaining("400");
    }

}
