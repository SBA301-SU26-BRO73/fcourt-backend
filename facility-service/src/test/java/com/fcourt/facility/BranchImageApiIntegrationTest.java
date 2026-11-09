package com.fcourt.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.facility.service.ImageStorage;
import java.util.ArrayList;
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

/** Branch gallery against a real Postgres and a fake image host (the S3 adapter has its own tests). */
@SpringBootTest(properties = {
        "facility.images.max-per-branch=3",
        "facility.events.relay-delay-ms=200",
        "facility.events.topic-partitions=1",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"})
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1, topics = "facility.branch-updated.v1")
@Import(BranchImageApiIntegrationTest.FakeStorageConfig.class)
class BranchImageApiIntegrationTest {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};

    /** In-memory image host: remembers live files and can be told to fail the Nth upload. */
    static class FakeStorage implements ImageStorage {
        final List<String> live = new CopyOnWriteArrayList<>();
        final AtomicInteger counter = new AtomicInteger();
        volatile int failOnUpload = -1;
        volatile boolean failDelete;

        @Override
        public Stored store(String folder, Upload upload) {
            int n = counter.incrementAndGet();
            if (n == failOnUpload) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Image storage request failed");
            String publicId = folder + "/img" + n;
            live.add(publicId);
            return new Stored("https://cdn.example.com/" + publicId + ".png", publicId);
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

    @BeforeEach
    void reset() {
        storage.failOnUpload = -1;
        storage.failDelete = false;
        storage.counter.set(0);
        storage.live.clear();
    }

    static JwtRequestPostProcessor as(long userId, String role) {
        return jwt().jwt(token -> token.subject(Long.toString(userId)).claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    long createBranch(long owner, String name) throws Exception {
        String body = """
                {"name":"%s","address":"1 Nguyen Trai","city":"Ho Chi Minh","openTime":"06:00","closeTime":"22:00",
                 "bankName":"Vietcombank","bankBin":"970436","bankAccountNumber":"0123456789","bankAccountName":"NGUYEN VAN A"}
                """.formatted(name);
        var result = mvc.perform(post("/api/admin/branches").with(as(owner, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    ResultActions upload(long branchId, long user, String role, MockMultipartFile... files) throws Exception {
        var request = multipart("/api/admin/branches/{id}/images", branchId);
        for (var file : files) request.file(file);
        return mvc.perform(request.with(as(user, role)));
    }

    static MockMultipartFile png(String name) {
        return new MockMultipartFile("files", name, "image/png", PNG);
    }

    @Test
    void uploadsManyImagesAndReadsThemBackThroughBranchDetailAndPublicGallery() throws Exception {
        long owner = 9301;
        long branchId = createBranch(owner, "Gallery A");
        upload(branchId, owner, "ADMIN", png("a.png"), png("b.png"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].branchId").value(branchId))
                .andExpect(jsonPath("$[0].url").value("https://cdn.example.com/branches/" + branchId + "/img1.png"))
                .andExpect(jsonPath("$[0].sortOrder").value(0))
                .andExpect(jsonPath("$[1].sortOrder").value(1));

        mvc.perform(get("/api/admin/branches/{id}", branchId).with(as(owner, "ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.images", hasSize(2)));
        mvc.perform(get("/api/admin/branches").with(as(owner, "ADMIN")))
                .andExpect(jsonPath("$[0].images", hasSize(2)));
        mvc.perform(get("/api/public/branches/{id}/images", branchId))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void enforcesOwnershipRoleAndLimits() throws Exception {
        long owner = 9302;
        long branchId = createBranch(owner, "Gallery B");
        upload(branchId, 9999, "ADMIN", png("a.png")).andExpect(status().isNotFound());
        upload(branchId, owner, "CUSTOMER", png("a.png")).andExpect(status().isForbidden());
        upload(branchId, owner, "ADMIN").andExpect(status().isBadRequest());
        upload(branchId, owner, "ADMIN", new MockMultipartFile("files", "x.png", "image/png", "<svg/>".getBytes()))
                .andExpect(status().isBadRequest());
        assertThat(storage.live).isEmpty();

        upload(branchId, owner, "ADMIN", png("1.png"), png("2.png"), png("3.png")).andExpect(status().isCreated());
        upload(branchId, owner, "ADMIN", png("4.png")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A branch can have at most 3 images"));
        assertThat(storage.live).hasSize(3);

        mvc.perform(get("/api/public/branches/{id}/images", 987654)).andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/branches/{id}/images", branchId)).andExpect(status().isUnauthorized());
    }

    @Test
    void failedUploadLeavesNoOrphanFileOrRow() throws Exception {
        long owner = 9303;
        long branchId = createBranch(owner, "Gallery C");
        storage.failOnUpload = 2;
        upload(branchId, owner, "ADMIN", png("a.png"), png("b.png")).andExpect(status().isBadGateway());
        assertThat(storage.live).isEmpty();
        mvc.perform(get("/api/admin/branches/{id}/images", branchId).with(as(owner, "ADMIN")))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void deleteRemovesRemoteFileAndRowButKeepsRowWhenHostFails() throws Exception {
        long owner = 9304;
        long branchId = createBranch(owner, "Gallery D");
        var created = upload(branchId, owner, "ADMIN", png("a.png")).andExpect(status().isCreated());
        long imageId = json.readTree(created.andReturn().getResponse().getContentAsString()).get(0).get("id").asLong();

        mvc.perform(delete("/api/admin/branches/images/{id}", imageId).with(as(9999, "ADMIN")))
                .andExpect(status().isNotFound());
        storage.failDelete = true;
        mvc.perform(delete("/api/admin/branches/images/{id}", imageId).with(as(owner, "ADMIN")))
                .andExpect(status().isBadGateway());
        mvc.perform(get("/api/admin/branches/{id}/images", branchId).with(as(owner, "ADMIN")))
                .andExpect(jsonPath("$", hasSize(1)));

        storage.failDelete = false;
        mvc.perform(delete("/api/admin/branches/images/{id}", imageId).with(as(owner, "ADMIN")))
                .andExpect(status().isNoContent());
        assertThat(storage.live).isEmpty();
        mvc.perform(delete("/api/admin/branches/images/{id}", imageId).with(as(owner, "ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/branches/{id}", branchId).with(as(owner, "ADMIN")))
                .andExpect(jsonPath("$.images", hasSize(0)));
    }

    @Test
    void superAdminCanManageAnyBranchGallery() throws Exception {
        long owner = 9305;
        long branchId = createBranch(owner, "Gallery E");
        upload(branchId, 1, "SUPER_ADMIN", png("a.png")).andExpect(status().isCreated());
        var ids = new ArrayList<Long>();
        var list = mvc.perform(get("/api/admin/branches/{id}/images", branchId).with(as(1, "SUPER_ADMIN")))
                .andExpect(status().isOk());
        json.readTree(list.andReturn().getResponse().getContentAsString()).forEach(node -> ids.add(node.get("id").asLong()));
        mvc.perform(delete("/api/admin/branches/images/{id}", ids.get(0)).with(as(1, "SUPER_ADMIN")))
                .andExpect(status().isNoContent());
    }
}
