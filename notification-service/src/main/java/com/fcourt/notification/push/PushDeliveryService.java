package com.fcourt.notification.push;

import com.fcourt.notification.device.UserDeviceRepository;
import com.fcourt.notification.event.EventEnvelope;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
public class PushDeliveryService {
    private final PushPlanner planner;
    private final UserDeviceRepository devices;
    private final PushDeviceDelivery delivery;

    public PushDeliveryService(PushPlanner planner, UserDeviceRepository devices, PushDeviceDelivery delivery) {
        this.planner = planner; this.devices = devices; this.delivery = delivery;
    }

    public void send(EventEnvelope event) {
        PushPlan plan = planner.plan(event);
        if (plan == null) return;
        for (var device : devices.activeDevices(plan.userId())) {
            delivery.send(plan, device);
        }
    }

    public void sendReminder(long bookingId, long userId) {
        UUID reminderId = UUID.nameUUIDFromBytes(("reminder:" + bookingId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var plan = new PushPlan(reminderId, userId, bookingId, "BOOKING_REMINDER",
                "Nhắc lịch chơi sân", "Lịch đặt sân của bạn sắp bắt đầu.");
        for (var device : devices.activeDevices(userId)) {
            delivery.send(plan, device);
        }
    }
}
