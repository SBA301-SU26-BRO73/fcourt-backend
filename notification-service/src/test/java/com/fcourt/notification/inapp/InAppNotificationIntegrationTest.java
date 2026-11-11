package com.fcourt.notification.inapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fcourt.notification.device.PushEndpointGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;

/** Real Postgres and Flyway: the REST API scoped by user, and idempotent writes. Needs Docker. */
@SpringBootTest(properties = "notification.enabled=false")
@AutoConfigureMockMvc
class InAppNotificationIntegrationTest {
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        DATABASE.start();
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired InAppNotificationRepository repository;
    @MockitoBean PushEndpointGateway gateway;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM in_app_notifications");
    }

    @Test
    void requiresAnAuthenticatedUser() throws Exception {
        mvc.perform(get("/api/notifications/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications/me/unread-count")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/notifications/me/read-all")).andExpect(status().isUnauthorized());
    }

    @Test
    void theSameUserAndKeyNeverDuplicates() {
        var plan = new InAppPlan(41, "BOOKING_CONFIRMED", "Tiêu đề", "Nội dung", "/my-bookings", "booking-confirmed:1");

        assertThat(repository.insert(plan)).isTrue();
        assertThat(repository.insert(plan)).isFalse();
        // The same fact for another user is a different row.
        assertThat(repository.insert(new InAppPlan(42, "BOOKING_CONFIRMED", "Tiêu đề", "Nội dung", "/x",
                "booking-confirmed:1"))).isTrue();

        assertThat(repository.count(41)).isEqualTo(1);
        assertThat(repository.count(42)).isEqualTo(1);
    }

    @Test
    void listsOnlyMyRowsNewestFirstWithPaging() throws Exception {
        long first = insert(41, "k1", "Cũ nhất");
        insert(41, "k2", "Giữa");
        insert(41, "k3", "Mới nhất");
        insert(99, "k1", "Của người khác");

        mvc.perform(get("/api/notifications/me?page=0&size=2").with(user(41)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].title").value("Mới nhất"))
                .andExpect(jsonPath("$.items[1].title").value("Giữa"))
                .andExpect(jsonPath("$.items[0].readAt").doesNotExist());
        mvc.perform(get("/api/notifications/me?page=1&size=2").with(user(41)))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(first));
    }

    @Test
    void countsUnreadAndMarksOneRead() throws Exception {
        long id = insert(41, "k1", "A");
        insert(41, "k2", "B");

        mvc.perform(get("/api/notifications/me/unread-count").with(user(41)))
                .andExpect(jsonPath("$.count").value(2));
        mvc.perform(patch("/api/notifications/" + id + "/read").with(user(41))).andExpect(status().isNoContent());
        mvc.perform(patch("/api/notifications/" + id + "/read").with(user(41))).andExpect(status().isNoContent());
        mvc.perform(get("/api/notifications/me/unread-count").with(user(41)))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void anotherUsersNotificationLooksMissingAndStaysUnread() throws Exception {
        long id = insert(41, "k1", "A");

        mvc.perform(patch("/api/notifications/" + id + "/read").with(user(42))).andExpect(status().isNotFound());
        mvc.perform(patch("/api/notifications/999999/read").with(user(42))).andExpect(status().isNotFound());

        assertThat(repository.unread(41)).isEqualTo(1);
    }

    @Test
    void readAllOnlyTouchesMyRows() throws Exception {
        insert(41, "k1", "A");
        insert(41, "k2", "B");
        insert(42, "k1", "C");

        mvc.perform(patch("/api/notifications/me/read-all").with(user(41)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2));

        assertThat(repository.unread(41)).isZero();
        assertThat(repository.unread(42)).isEqualTo(1);
    }

    @Test
    void aTokenWithoutAUserSubjectIsRejected() throws Exception {
        mvc.perform(get("/api/notifications/me/unread-count").with(jwt().jwt(token -> token.subject("abc"))))
                .andExpect(status().isUnauthorized());
    }

    private long insert(long user, String key, String title) {
        repository.insert(new InAppPlan(user, "TEST", title, "Nội dung", "/my-bookings", key));
        return jdbc.queryForObject("SELECT id FROM in_app_notifications WHERE user_id = ? AND dedupe_key = ?",
                Long.class, user, key);
    }

    private static RequestPostProcessor user(long id) {
        return jwt().jwt(token -> token.subject(Long.toString(id)));
    }
}
