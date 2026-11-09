package com.fcourt.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.facility.service.ImageStorage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/** Court photo upload, replace and removal against a real Postgres and a fake image host. */
@SpringBootTest(properties = {
        "facility.subscription.default-max-courts=10",
        "facility.events.relay-delay-ms=200",
        "facility.events.topic-partitions=1",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"})
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1, topics = "facility.branch-updated.v1")
@Import(CourtImageApiIntegrationTest.FakeStorageConfig.class)
class CourtImageApiIntegrationTest {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};

    static class FakeStorage implements ImageStorage {
        final List<String> live = new CopyOnWriteArrayList<>();
        final AtomicInteger counter = new AtomicInteger();
        volatile boolean failUpload;
        volatile boolean failDelete;

        @Override
        public Stored store(String folder, Upload upload) {
            if (failUpload) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Image storage request failed");
            String publicId = folder + "/img" + counter.incrementAndGet() + ".png"; // like S3: the key keeps its extension
            live.add(publicId);
            return new Stored("https://cdn.example.com/" + publicId, publicId);
        }

        @Override
        public void delete(String publicId) {
            if (failDelete) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Image storage request failed");
            live.remove(publicId);
        }
    }

    @TestConfiguration
    static class FakeStorageConfig {
        @Bean
        @Primary
        FakeStorage fakeStorage() {
            return new FakeStorage();
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired FakeStorage storage;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        storage.failUpload = false;
        storage.failDelete = false;
        storage.counter.set(0);
        storage.live.clear();
    }

    static JwtRequestPostProcessor as(long userId, String role) {
        return jwt().jwt(token -> token.subject(Long.toString(userId)).claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    long create(String url, String body, long owner) throws Exception {
        var result = mvc.perform(post(url).with(as(owner, "ADMIN")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    /** Returns {branchId, courtId}. */
    long[] court(long owner, String name, String imageUrl) throws Exception {
        long branchId = create("/api/admin/branches", """
                {"name":"%s","address":"1 Nguyen Trai","city":"Ho Chi Minh","openTime":"06:00","closeTime":"22:00",
                 "bankName":"Vietcombank","bankBin":"970436","bankAccountNumber":"0123456789","bankAccountName":"NGUYEN VAN A"}
                """.formatted(name), owner);
        String image = imageUrl == null ? "null" : "\"" + imageUrl + "\"";
        long courtId = create("/api/admin/branches/" + branchId + "/courts", """
                {"name":"Court 1","courtTypeId":6,"imageUrl":%s,"defaultPricePerHour":100000}
                """.formatted(image), owner);
        return new long[] {branchId, courtId};
    }

    ResultActions upload(long courtId, long user, String role, MockMultipartFile... files) throws Exception {
        var request = multipart("/api/admin/courts/{id}/image", courtId);
        for (var file : files) request.file(file);
        return mvc.perform(request.with(as(user, role)));
    }

    static MockMultipartFile png() {
        return new MockMultipartFile("file", "court.png", "image/png", PNG);
    }

    int outboxCount() {
        return jdbc.queryForObject("SELECT count(*) FROM facility_outbox", Integer.class);
    }

    @Test
    void uploadStoresUnderTheCourtFolderUpdatesImageUrlAndEmitsTheBranchSnapshot() throws Exception {
        long owner = 9401;
        long[] ids = court(owner, "Court img A", "https://example.com/old.jpg");
        int events = outboxCount();
        String expected = "https://cdn.example.com/branches/" + ids[0] + "/courts/" + ids[1] + "/img1.png";
        upload(ids[1], owner, "ADMIN", png()).andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value(expected));
        assertThat(storage.live).containsExactly("branches/" + ids[0] + "/courts/" + ids[1] + "/img1.png");
        assertThat(outboxCount()).isGreaterThan(events);
        mvc.perform(get("/api/admin/courts/{id}", ids[1]).with(as(owner, "ADMIN")))
                .andExpect(jsonPath("$.imageUrl").value(expected));
    }

    @Test
    void replacingRemovesTheOldOwnFileButNeverAForeignUrl() throws Exception {
        long owner = 9402;
        long[] ids = court(owner, "Court img B", null);
        upload(ids[1], owner, "ADMIN", png()).andExpect(status().isOk());
        upload(ids[1], owner, "ADMIN", png()).andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value(org.hamcrest.Matchers.endsWith("/img2.png")));
        assertThat(storage.live).containsExactly("branches/" + ids[0] + "/courts/" + ids[1] + "/img2.png");

        long[] other = court(owner, "Court img C", "https://cdn.example.com/branches/1/img9.png");
        upload(other[1], owner, "ADMIN", png()).andExpect(status().isOk());
        assertThat(storage.live).hasSize(2); // the gallery-style URL was not deleted remotely
    }

    @Test
    void rejectsNonImagesMissingFilesWrongOwnerRoleAndAnonymous() throws Exception {
        long owner = 9403;
        long[] ids = court(owner, "Court img D", null);
        upload(ids[1], owner, "ADMIN", new MockMultipartFile("file", "x.png", "image/png", "<svg/>".getBytes()))
                .andExpect(status().isBadRequest());
        upload(ids[1], owner, "ADMIN").andExpect(status().isBadRequest());
        upload(ids[1], 9999, "ADMIN", png()).andExpect(status().isNotFound());
        upload(ids[1], owner, "CUSTOMER", png()).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/admin/courts/{id}/image", ids[1]).file(png())).andExpect(status().isUnauthorized());
        assertThat(storage.live).isEmpty();
    }

    @Test
    void hostFailureKeepsTheCurrentImageAndLeavesNoOrphan() throws Exception {
        long owner = 9404;
        long[] ids = court(owner, "Court img E", "https://example.com/keep.jpg");
        storage.failUpload = true;
        upload(ids[1], owner, "ADMIN", png()).andExpect(status().isBadGateway());
        mvc.perform(get("/api/admin/courts/{id}", ids[1]).with(as(owner, "ADMIN")))
                .andExpect(jsonPath("$.imageUrl").value("https://example.com/keep.jpg"));
        assertThat(storage.live).isEmpty();
    }

    @Test
    void failureToDeleteTheOldFileDoesNotFailTheReplacement() throws Exception {
        long owner = 9405;
        long[] ids = court(owner, "Court img F", null);
        upload(ids[1], owner, "ADMIN", png()).andExpect(status().isOk());
        storage.failDelete = true;
        upload(ids[1], owner, "ADMIN", png()).andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value(org.hamcrest.Matchers.endsWith("/img2.png")));
    }

    @Test
    void deleteClearsTheImageAndRemovesTheFileAndSuperAdminMayManageAnyCourt() throws Exception {
        long owner = 9406;
        long[] ids = court(owner, "Court img G", null);
        upload(ids[1], 1, "SUPER_ADMIN", png()).andExpect(status().isOk());
        mvc.perform(delete("/api/admin/courts/{id}/image", ids[1]).with(as(9999, "ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/admin/courts/{id}/image", ids[1]).with(as(owner, "ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").doesNotExist());
        assertThat(storage.live).isEmpty();
    }
}
