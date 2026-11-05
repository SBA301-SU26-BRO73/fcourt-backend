package com.fcourt.discovery;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fcourt.discovery.component.*;
import com.fcourt.discovery.dto.request.*;
import com.fcourt.discovery.model.event.*;
import com.fcourt.discovery.repository.*;
import com.fcourt.discovery.service.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
@AutoConfigureMockMvc
@DirtiesContext
class ReviewIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final RSAKey KEY = signingKey();
    static final String ISSUER = "http://reviews-test/auth";
    static final Map<Long,ObjectNode> BOOKINGS = new ConcurrentHashMap<>();
    static final AtomicReference<String> BRANCHES = new AtomicReference<>();
    static final AtomicReference<String> BOOKING_AUTH = new AtomicReference<>();
    static final AtomicReference<String> FACILITY_AUTH = new AtomicReference<>();
    static final AtomicInteger BOOKING_STATUS = new AtomicInteger(200), FACILITY_STATUS = new AtomicInteger(200);
    static final AtomicInteger BOOKING_CALLS = new AtomicInteger(), FACILITY_CALLS = new AtomicInteger();
    static final HttpServer SOURCE = startSources();

    static RSAKey signingKey() {
        try { return new RSAKeyGenerator(2048).keyID("reviews-key").generate(); }
        catch(Exception error){throw new ExceptionInInitializerError(error);}
    }
    static boolean owner(String header,long user) {
        if (("Bearer customer-"+user).equals(header)) return true;
        if(header==null || !header.startsWith("Bearer ")) return false;
        try {
            var signed=com.nimbusds.jwt.SignedJWT.parse(header.substring(7));
            return Long.toString(user).equals(signed.getJWTClaimsSet().getSubject())
                    && signed.verify(new com.nimbusds.jose.crypto.RSASSAVerifier(KEY.toPublicJWK()));
        } catch(Exception error){return false;}
    }
    static HttpServer startSources() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/bookings/",exchange->{
                BOOKING_CALLS.incrementAndGet();String auth=exchange.getRequestHeaders().getFirst("Authorization");BOOKING_AUTH.set(auth);
                long id=Long.parseLong(exchange.getRequestURI().getPath().split("/")[3]);var order=BOOKINGS.get(id);
                int status=order==null || !owner(auth,order.path("customerId").asLong())?404:BOOKING_STATUS.get();
                byte[] bytes=(order==null?"{}":order.toString()).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);
                try(var out=exchange.getResponseBody()){out.write(bytes);}
            });
            server.createContext("/api/admin/branches",exchange->{
                FACILITY_CALLS.incrementAndGet();FACILITY_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes=BRANCHES.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(FACILITY_STATUS.get(),bytes.length);
                try(var out=exchange.getResponseBody()){out.write(bytes);}
            });
            server.createContext("/.well-known/jwks.json",exchange->{
                byte[] bytes=new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);
                try(var out=exchange.getResponseBody()){out.write(bytes);}
            });
            server.start();return server;
        } catch(java.io.IOException error){throw new ExceptionInInitializerError(error);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",DB::getJdbcUrl);registry.add("spring.datasource.username",DB::getUsername);
        registry.add("spring.datasource.password",DB::getPassword);
        registry.add("discovery.slots.booking-url",()->"http://127.0.0.1:"+SOURCE.getAddress().getPort());
        registry.add("discovery.slots.facility-url",()->"http://127.0.0.1:"+SOURCE.getAddress().getPort());
        registry.add("discovery.auth.jwk-set-uri",()->"http://127.0.0.1:"+SOURCE.getAddress().getPort()+"/.well-known/jwks.json");
        registry.add("discovery.auth.issuer",()->ISSUER);
    }
    @AfterAll static void stop(){SOURCE.stop(0);}
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestRestTemplate http;
    @Autowired ReviewService service;
    @Autowired BranchSearchService search;
    @Autowired BranchSearchRepository searchRepository;
    @Autowired BranchProjection projection;
    @MockitoSpyBean ReviewRepository reviewRepository;

    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE reviews,branch_review_stats,branch_read_model RESTART IDENTITY CASCADE");
        jdbc.update("UPDATE review_cache_version SET version=0 WHERE id=1");
        projection.apply(snapshot(7,1,true));projection.apply(snapshot(8,1,true));
        BOOKINGS.clear();order(1,10,7,11);order(2,20,7,12);order(3,30,7,11);order(4,10,8,21);
        BRANCHES.set("[{\"id\":7,\"adminId\":100}]");
        BOOKING_CALLS.set(0);FACILITY_CALLS.set(0);BOOKING_STATUS.set(200);FACILITY_STATUS.set(200);
    }
    BranchUpdatedEvent snapshot(long id,long version,boolean active) {
        return new BranchUpdatedEvent(id,version,"Branch "+id,"Address","City","District",10.0,106.0,active,false,
                List.of(new BranchUpdatedEvent.CourtSnapshot(id==7?11L:21L,1L,new java.math.BigDecimal("75000"),new java.math.BigDecimal("150000"))));
    }
    void order(long id,long user,long branch,long court) {
        BOOKINGS.put(id,JSON.createObjectNode().put("id",id).put("customerId",user).put("branchId",branch).put("courtId",court).put("status","COMPLETED"));
    }
    Jwt actor(long id,String role) {
        return Jwt.withTokenValue((role.equals("CUSTOMER")?"customer-":"admin-")+id).header("alg","RS256").subject(Long.toString(id))
                .claim("roles",List.of(role)).build();
    }
    long create(long booking,long user,int rating) throws Exception {
        String body="{\"bookingId\":"+booking+",\"rating\":"+rating+",\"comment\":\"Good court\",\"imageUrls\":[\"https://example.test/court.jpg\"]}";
        var result=mvc.perform(post("/api/reviews").with(jwt().jwt(actor(user,"CUSTOMER"))).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(result).path("id").asLong();
    }

    @Test void completedOwnerCanCreateReviewAndPublicResponseHidesBookingSecrets() throws Exception {
        long id=create(1,10,5);
        assertThat(BOOKING_AUTH.get()).isEqualTo("Bearer customer-10");
        mvc.perform(get("/api/public/branches/7/reviews")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.content[0].id").value(id)).andExpect(jsonPath("$.content[0].courtId").value(11))
                .andExpect(jsonPath("$.content[0].userId").value(10)).andExpect(jsonPath("$.content[0].bookingId").doesNotExist())
                .andExpect(jsonPath("$.content[0].checkInCode").doesNotExist()).andExpect(jsonPath("$.content[0].holdToken").doesNotExist())
                .andExpect(jsonPath("$.averageRating").value(5)).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test void onlyCompletedOwnedBookingsAndCustomerRoleCanBeReviewed() throws Exception {
        String body="{\"bookingId\":1,\"rating\":5}";
        mvc.perform(post("/api/reviews").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/reviews").with(jwt().jwt(actor(100,"ADMIN"))).contentType("application/json").content(body)).andExpect(status().isForbidden());
        assertThat(BOOKING_CALLS.get()).isZero();
        mvc.perform(post("/api/reviews").with(jwt().jwt(actor(20,"CUSTOMER"))).contentType("application/json").content(body)).andExpect(status().isNotFound());
        for(String state:List.of("PENDING_PAYMENT","AWAITING_CONFIRMATION","CONFIRMED","CHECKED_IN","CANCELLED")){
            BOOKINGS.get(1L).put("status",state);
            mvc.perform(post("/api/reviews").with(jwt().jwt(actor(10,"CUSTOMER"))).contentType("application/json").content(body)).andExpect(status().isConflict());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reviews",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM review_cache_version",Long.class)).isZero();
        BOOKINGS.get(1L).put("status","COMPLETED");
        mvc.perform(post("/api/reviews").with(jwt().jwt(actor(10,"CUSTOMER"))).contentType("application/json")
                        .content("{\"bookingId\":1,\"rating\":5,\"branchId\":999,\"courtId\":999,\"userId\":999}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.branchId").value(7)).andExpect(jsonPath("$.courtId").value(11))
                .andExpect(jsonPath("$.userId").value(10));
    }

    @Test void invalidStarsCommentsAndImageUrlsFailBeforeCallingBooking() throws Exception {
        var requests=new ArrayList<ObjectNode>();
        var base=JSON.createObjectNode().put("bookingId",1).put("rating",5);
        for(int rating:List.of(0,6)) requests.add(base.deepCopy().put("rating",rating));
        requests.add(base.deepCopy().put("rating",2.5));requests.add(base.deepCopy().putNull("rating"));
        requests.add(base.deepCopy().put("bookingId",0));requests.add(base.deepCopy().put("bookingId",1.5));
        requests.add(base.deepCopy().put("comment","x".repeat(2001)));
        for(String url:List.of("http://example.test/a.jpg","data:image/png;base64,abc","https://user:password@example.test/a.jpg","https://bad host/a.jpg","https://example.test/"+"x".repeat(2048))){
            var input=base.deepCopy();input.putArray("imageUrls").add(url);requests.add(input);
        }
        var many=base.deepCopy();for(int i=0;i<6;i++)many.withArray("imageUrls").add("https://example.test/"+i+".jpg");requests.add(many);
        var nullImage=base.deepCopy();nullImage.putArray("imageUrls").addNull();requests.add(nullImage);
        for(var input:requests) mvc.perform(post("/api/reviews").with(jwt().jwt(actor(10,"CUSTOMER"))).contentType("application/json").content(input.toString()))
                .andExpect(status().isBadRequest());
        assertThat(BOOKING_CALLS.get()).isZero();
    }

    @Test void concurrentDuplicateBookingReviewsOnlyCountOneVote() throws Exception {
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var first=executor.submit(()->{start.await();try{service.create(new CreateReviewRequest(1L,5,null,null),actor(10,"CUSTOMER"));return 201;}catch(org.springframework.web.server.ResponseStatusException error){return error.getStatusCode().value();}});
            var second=executor.submit(()->{start.await();try{service.create(new CreateReviewRequest(1L,1,null,null),actor(10,"CUSTOMER"));return 201;}catch(org.springframework.web.server.ResponseStatusException error){return error.getStatusCode().value();}});
            start.countDown();assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reviews",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT review_count FROM branch_review_stats",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM review_cache_version",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT rating_sum FROM branch_review_stats",Long.class))
                .isEqualTo(jdbc.queryForObject("SELECT rating FROM reviews",Long.class));
    }

    @Test void averagesDistributionAndPagingUseAllBranchVotes() throws Exception {
        create(1,10,5);create(2,20,3);long newest=create(3,30,1);
        jdbc.update("UPDATE reviews SET created_at='2030-01-01T00:00:00Z'");
        mvc.perform(get("/api/public/branches/7/reviews?size=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(newest)).andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(3)).andExpect(jsonPath("$.averageRating").value(3))
                .andExpect(jsonPath("$.starDistribution.length()").value(5))
                .andExpect(jsonPath("$.starDistribution[0].rating").value(1)).andExpect(jsonPath("$.starDistribution[0].count").value(1))
                .andExpect(jsonPath("$.starDistribution[0].percentage").value(33.33)).andExpect(jsonPath("$.starDistribution[1].count").value(0));
        mvc.perform(get("/api/public/branches/7/reviews?size=1&page=99")).andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.averageRating").value(3));
        mvc.perform(get("/api/public/branches/8/reviews")).andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.averageRating").value(0)).andExpect(jsonPath("$.starDistribution[4].percentage").value(0));
        for(String query:List.of("page=-1","page=1000001","size=0","size=101"))
            mvc.perform(get("/api/public/branches/7/reviews?"+query)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/branches/999/reviews")).andExpect(status().isNotFound());
    }

    @Test void branchOwnerCanUpdateReplyAndItIsImmediatelyPublic() throws Exception {
        long id=create(1,10,5);String path="/api/admin/reviews/"+id+"/reply";
        mvc.perform(post(path).contentType("application/json").content("{\"reply\":\"Thanks\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(jwt().jwt(actor(10,"CUSTOMER"))).contentType("application/json").content("{\"reply\":\"fake\"}"))
                .andExpect(status().isForbidden());
        for(String reply:List.of(" ","x".repeat(2001)))
            mvc.perform(post(path).with(jwt().jwt(actor(100,"ADMIN"))).contentType("application/json").content(JSON.createObjectNode().put("reply",reply).toString()))
                    .andExpect(status().isBadRequest());
        assertThat(FACILITY_CALLS.get()).isZero();
        mvc.perform(post(path).with(jwt().jwt(actor(100,"ADMIN"))).contentType("application/json").content("{\"reply\":\" Thanks for playing \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.adminReply").value("Thanks for playing"));
        assertThat(FACILITY_AUTH.get()).isEqualTo("Bearer admin-100");
        BRANCHES.set("[{\"id\":8,\"adminId\":200}]");
        mvc.perform(post(path).with(jwt().jwt(actor(200,"ADMIN"))).contentType("application/json").content("{\"reply\":\"foreign\"}"))
                .andExpect(status().isNotFound());
        BRANCHES.set("[{\"id\":7,\"adminId\":100}]");
        mvc.perform(post(path).with(jwt().jwt(actor(100,"ADMIN"))).contentType("application/json").content("{\"reply\":\"Updated reply\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/branches/7/reviews")).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].adminReply").value("Updated reply"))
                .andExpect(jsonPath("$.content[0].repliedAt").isNotEmpty());
        assertThat(jdbc.queryForObject("SELECT version FROM review_cache_version",Long.class)).isEqualTo(1);
    }

    @Test void committedRatingRevisionRefreshesSearchCachesAcrossInstances() throws Exception {
        var request=new BranchSearchRequest(null,null,null,null,null,null,null,null,null,null);
        var other=new BranchSearchService(searchRepository,60);
        var initial=search.search(request);var initialOther=other.search(request);
        assertThat(initial.content().getFirst().reviewCount()).isZero();assertThat(other.search(request)).isSameAs(initialOther);
        create(1,10,5);
        assertThat(search.search(request).content().getFirst().averageRating()).isEqualByComparingTo("5");
        assertThat(other.search(request).content().getFirst().reviewCount()).isEqualTo(1);
        create(2,20,1);
        assertThat(other.search(request).content().getFirst().averageRating()).isEqualByComparingTo("3");
        mvc.perform(get("/api/public/branches")).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].averageRating").value(3))
                .andExpect(jsonPath("$.content[0].reviewCount").value(2));
    }

    @Test void failedRatingUpdateRollsBackReviewCountersAndSharedCacheVersion() {
        org.mockito.Mockito.doAnswer(invocation->{invocation.callRealMethod();throw new IllegalStateException("simulated rating failure");})
                .when(reviewRepository).updateRating(7,5);
        assertThatThrownBy(()->service.create(new CreateReviewRequest(1L,5,null,null),actor(10,"CUSTOMER"))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reviews",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM branch_review_stats",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM review_cache_version",Long.class)).isZero();
    }

    @Test void independentBookingsKeepAccurateTotalsAndSurviveBranchSnapshotReplacement() throws Exception {
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var one=executor.submit(()->{start.await();return service.create(new CreateReviewRequest(1L,5,null,null),actor(10,"CUSTOMER"));});
            var two=executor.submit(()->{start.await();return service.create(new CreateReviewRequest(2L,3,null,null),actor(20,"CUSTOMER"));});
            start.countDown();one.get(10,TimeUnit.SECONDS);two.get(10,TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT review_count FROM branch_review_stats",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT rating_sum FROM branch_review_stats",Long.class)).isEqualTo(8);
        projection.apply(snapshot(7,2,true));
        mvc.perform(get("/api/public/branches/7/reviews")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.averageRating").value(4));
        projection.apply(snapshot(7,3,false));mvc.perform(get("/api/public/branches/7/reviews")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reviews",Long.class)).isEqualTo(2);
    }

    @Test void invalidBookingAndOwnershipSourcesFailClosedWithoutSavingData() throws Exception {
        String body="{\"bookingId\":1,\"rating\":5}";
        BOOKING_STATUS.set(500);
        mvc.perform(post("/api/reviews").with(jwt().jwt(actor(10,"CUSTOMER"))).contentType("application/json").content(body)).andExpect(status().isServiceUnavailable());
        BOOKING_STATUS.set(200);BOOKINGS.get(1L).put("courtId",0);
        mvc.perform(post("/api/reviews").with(jwt().jwt(actor(10,"CUSTOMER"))).contentType("application/json").content(body)).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reviews",Long.class)).isZero();
        BOOKINGS.get(1L).put("courtId",11);long id=create(1,10,5);String path="/api/admin/reviews/"+id+"/reply";
        for(String invalid:List.of("null","{}","[null]","[{\"id\":7,\"adminId\":999}]","[{\"id\":7,\"adminId\":100},{\"id\":7,\"adminId\":100}]")){
            BRANCHES.set(invalid);mvc.perform(post(path).with(jwt().jwt(actor(100,"ADMIN"))).contentType("application/json").content("{\"reply\":\"Thanks\"}"))
                    .andExpect(status().isServiceUnavailable());
        }
        FACILITY_STATUS.set(403);mvc.perform(post(path).with(jwt().jwt(actor(100,"ADMIN"))).contentType("application/json").content("{\"reply\":\"Thanks\"}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT admin_reply FROM reviews WHERE id=?",String.class,id)).isNull();
    }
}
