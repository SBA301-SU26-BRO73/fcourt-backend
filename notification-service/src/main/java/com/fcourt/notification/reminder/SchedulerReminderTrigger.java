package com.fcourt.notification.reminder;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.scheduler.model.CreateScheduleRequest;
import software.amazon.awssdk.services.scheduler.model.DeleteScheduleRequest;
import software.amazon.awssdk.services.scheduler.model.FlexibleTimeWindow;
import software.amazon.awssdk.services.scheduler.model.Target;

@Component
@ConditionalOnProperty(name="notification.push.reminder.enabled", havingValue="true")
class SchedulerReminderTrigger implements ReminderTrigger {
    private static final DateTimeFormatter EXPRESSION = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
    private final SchedulerClient scheduler;
    private final String group, queueArn, roleArn;
    SchedulerReminderTrigger(SchedulerClient scheduler,
            @Value("${notification.push.reminder.scheduler-group:fcourt-reminders}") String group,
            @Value("${notification.push.reminder.queue-arn:}") String queueArn,
            @Value("${notification.push.reminder.role-arn:}") String roleArn) {
        this.scheduler=scheduler; this.group=group; this.queueArn=queueArn; this.roleArn=roleArn;
    }
    public void create(long bookingId, Instant fireAt) {
        var request = CreateScheduleRequest.builder().name(name(bookingId)).groupName(group)
                .scheduleExpression("at(" + EXPRESSION.format(fireAt) + ")")
                .scheduleExpressionTimezone("Asia/Ho_Chi_Minh")
                .flexibleTimeWindow(FlexibleTimeWindow.builder().mode("OFF").build())
                .actionAfterCompletion("DELETE")
                .target(Target.builder().arn(queueArn).roleArn(roleArn)
                        .input("{\"bookingId\":" + bookingId + "}").build()).build();
        try { scheduler.createSchedule(request); }
        catch (software.amazon.awssdk.services.scheduler.model.ConflictException exists) {
            scheduler.updateSchedule(b -> b.name(name(bookingId)).groupName(group)
                    .scheduleExpression(request.scheduleExpression())
                    .scheduleExpressionTimezone(request.scheduleExpressionTimezone())
                    .flexibleTimeWindow(request.flexibleTimeWindow()).actionAfterCompletion("DELETE")
                    .target(request.target()));
        }
    }
    public void delete(long bookingId) {
        try { scheduler.deleteSchedule(DeleteScheduleRequest.builder().name(name(bookingId)).groupName(group).build()); }
        catch (software.amazon.awssdk.services.scheduler.model.ResourceNotFoundException ignored) { }
    }
    private String name(long id) { return "reminder-" + id; }
}
