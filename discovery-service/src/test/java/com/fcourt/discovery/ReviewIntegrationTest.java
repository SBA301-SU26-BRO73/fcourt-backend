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
}
