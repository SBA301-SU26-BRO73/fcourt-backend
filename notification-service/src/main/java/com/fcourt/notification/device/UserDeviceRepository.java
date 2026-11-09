package com.fcourt.notification.device;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class UserDeviceRepository {
    private final JdbcClient jdbc;

    UserDeviceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void upsert(long userId, DeviceRegistration device, String endpointArn) {
        jdbc.sql("""
                        INSERT INTO user_devices (user_id, device_id, platform, fcm_token, endpoint_arn)
                        VALUES (:userId, :deviceId, :platform, :token, :endpointArn)
                        ON CONFLICT (user_id, device_id) DO UPDATE
                        SET platform = EXCLUDED.platform,
                            fcm_token = EXCLUDED.fcm_token,
                            endpoint_arn = EXCLUDED.endpoint_arn,
                            updated_at = now()
                        """)
                .param("userId", userId)
                .param("deviceId", device.deviceId())
                .param("platform", device.platform())
                .param("token", device.token())
                .param("endpointArn", endpointArn)
                .update();
    }

    Optional<String> endpointArn(long userId, String deviceId) {
        return jdbc.sql("SELECT endpoint_arn FROM user_devices WHERE user_id = :userId AND device_id = :deviceId")
                .param("userId", userId)
                .param("deviceId", deviceId)
                .query(String.class)
                .optional();
    }

    boolean delete(long userId, String deviceId) {
        return jdbc.sql("DELETE FROM user_devices WHERE user_id = :userId AND device_id = :deviceId")
                        .param("userId", userId)
                        .param("deviceId", deviceId)
                        .update()
                > 0;
    }
}
