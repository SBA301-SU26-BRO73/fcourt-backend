package com.fcourt.notification.push;

import com.fcourt.notification.device.UserDeviceRepository;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sns.model.SnsException;

/** Commits idempotency per device so a later device's retry cannot resend earlier successful pushes. */
@Service
public class PushDeviceDelivery {
    private final JdbcTemplate jdbc;
    private final UserDeviceRepository devices;
    private final PushSender sender;
    public PushDeviceDelivery(JdbcTemplate jdbc, UserDeviceRepository devices, PushSender sender) {
        this.jdbc=jdbc; this.devices=devices; this.sender=sender;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void send(PushPlan plan, UserDeviceRepository.ActiveDevice device) {
        int claimed = jdbc.update("INSERT INTO push_deliveries(event_id, device_id, status) VALUES (?, ?, 'PENDING') ON CONFLICT DO NOTHING",
                plan.dedupEventId(), device.id());
        if (claimed == 0) return;
        var message = new PushMessage(plan.title(), plan.body(), Map.of("type", plan.type(),
                "notificationId", plan.dedupEventId().toString(), "bookingId", Long.toString(plan.bookingId()),
                "route", "/bookings/" + plan.bookingId()));
        try {
            sender.send(device.endpointArn(), message);
            jdbc.update("UPDATE push_deliveries SET status='SENT', sent_at=now(), updated_at=now() WHERE event_id=? AND device_id=?",
                    plan.dedupEventId(), device.id());
        } catch (SnsException ex) {
            if (!"EndpointDisabled".equals(ex.awsErrorDetails() == null ? null : ex.awsErrorDetails().errorCode())) throw ex;
            devices.disable(plan.userId(), device.deviceId());
            jdbc.update("UPDATE push_deliveries SET status='DISABLED', updated_at=now() WHERE event_id=? AND device_id=?",
                    plan.dedupEventId(), device.id());
        }
    }
}
