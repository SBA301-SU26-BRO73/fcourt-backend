package com.fcourt.notification.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
class DeviceControllerIntegrationTest {
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        DATABASE.start();
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PushEndpointGateway gateway;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @BeforeEach
    void resetDevices() {
        jdbc.update("DELETE FROM user_devices");
        when(gateway.register(any(), any(Long.class))).thenAnswer(invocation ->
                "arn:device:" + invocation.getArgument(1) + ":" + invocation.getArgument(0));
    }

    @Test
    void requiresAnAuthenticatedUser() throws Exception {
        mvc.perform(put("/devices").contentType("application/json")
                        .content("""
                                {"deviceId":"pixel-1","platform":"ANDROID","token":"fcm-token"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void putsAndUpdatesTheSameDeviceInsteadOfDuplicatingIt() throws Exception {
        mvc.perform(put("/devices").with(jwt().jwt(token -> token.subject("41")))
                        .contentType("application/json")
                        .content(registration("pixel-1", "token-first")))
                .andExpect(status().isNoContent());
        mvc.perform(put("/devices").with(jwt().jwt(token -> token.subject("41")))
                        .contentType("application/json")
                        .content(registration("pixel-1", "token-updated")))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_devices WHERE user_id = 41 AND device_id = 'pixel-1'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT fcm_token FROM user_devices WHERE user_id = 41 AND device_id = 'pixel-1'", String.class))
                .isEqualTo("token-updated");
        assertThat(jdbc.queryForObject(
                "SELECT endpoint_arn FROM user_devices WHERE user_id = 41 AND device_id = 'pixel-1'", String.class))
                .isEqualTo("arn:device:41:token-updated");
    }

    @Test
    void deleteRemovesOnlyTheCallersOwnDevice() throws Exception {
        jdbc.update("INSERT INTO user_devices (user_id, device_id, platform, fcm_token, endpoint_arn) VALUES (41, 'shared', 'ANDROID', 'token-41', 'arn:41')");
        jdbc.update("INSERT INTO user_devices (user_id, device_id, platform, fcm_token, endpoint_arn) VALUES (42, 'shared', 'ANDROID', 'token-42', 'arn:42')");

        mvc.perform(delete("/devices/shared").with(jwt().jwt(token -> token.subject("41"))))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_devices WHERE user_id = 41", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_devices WHERE user_id = 42", Integer.class))
                .isEqualTo(1);
        verify(gateway).delete("arn:41");
    }

    @Test
    void rejectsUnsupportedPlatforms() throws Exception {
        mvc.perform(put("/devices").with(jwt().jwt(token -> token.subject("41")))
                        .contentType("application/json")
                        .content(registration("pixel-1", "token", "IOS")))
                .andExpect(status().isBadRequest());
    }

    private String registration(String deviceId, String token) {
        return registration(deviceId, token, "ANDROID");
    }

    private String registration(String deviceId, String token, String platform) {
        return """
                {"deviceId":"%s","platform":"%s","token":"%s"}
                """.formatted(deviceId, platform, token);
    }
}
