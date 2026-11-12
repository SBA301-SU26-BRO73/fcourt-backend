# notification-service: repository reference

[Documentation index](../README.md) | [Service reference index](README.md)

This reference maps tracked files to their purpose for repository navigation. It describes file locations, not evidence that the service has been built, tested, or deployed.

## Service directory

The service lives in [`notification-service/`](../../notification-service/). Its build descriptor is [`pom.xml`](../../notification-service/pom.xml). Paths below are relative to the repository root. Generated output and untracked local configuration are excluded.

## Build and container entry points

Inspect the Maven descriptor for dependencies and plugins, and the Dockerfile or Compose descriptor when present for container behavior. These links identify definitions; they do not execute them.

- [Dockerfile](../../notification-service/Dockerfile)
- [mvnw](../../notification-service/mvnw)
- [mvnw.cmd](../../notification-service/mvnw.cmd)
- [pom.xml](../../notification-service/pom.xml)

## Application resources

Tracked application resources are listed below. Inspect property placeholders and profile selection before choosing local values; do not copy credentials into documentation.

- [application.yml](../../notification-service/src/main/resources/application.yml)
- [booking-cancelled.html](../../notification-service/src/main/resources/mail/booking-cancelled.html)
- [booking-cancelled.txt](../../notification-service/src/main/resources/mail/booking-cancelled.txt)
- [booking-confirmed.html](../../notification-service/src/main/resources/mail/booking-confirmed.html)
- [booking-confirmed.txt](../../notification-service/src/main/resources/mail/booking-confirmed.txt)
- [booking-reminder.html](../../notification-service/src/main/resources/mail/booking-reminder.html)
- [booking-reminder.txt](../../notification-service/src/main/resources/mail/booking-reminder.txt)
- [owner-approved.html](../../notification-service/src/main/resources/mail/owner-approved.html)
- [owner-approved.txt](../../notification-service/src/main/resources/mail/owner-approved.txt)
- [password-reset.html](../../notification-service/src/main/resources/mail/password-reset.html)
- [password-reset.txt](../../notification-service/src/main/resources/mail/password-reset.txt)

## Java source map

Java files are grouped by their tracked source directory. Package location alone does not establish an API contract or runtime dependency.

### `com/fcourt/notification`

- [NotificationApplication.java](../../notification-service/src/main/java/com/fcourt/notification/NotificationApplication.java)

### `com/fcourt/notification/config`

- [OpenApiConfig.java](../../notification-service/src/main/java/com/fcourt/notification/config/OpenApiConfig.java)

### `com/fcourt/notification/device`

- [DeviceController.java](../../notification-service/src/main/java/com/fcourt/notification/device/DeviceController.java)
- [DeviceRegistration.java](../../notification-service/src/main/java/com/fcourt/notification/device/DeviceRegistration.java)
- [DeviceSecurity.java](../../notification-service/src/main/java/com/fcourt/notification/device/DeviceSecurity.java)
- [DeviceService.java](../../notification-service/src/main/java/com/fcourt/notification/device/DeviceService.java)
- [LoggingPushEndpointGateway.java](../../notification-service/src/main/java/com/fcourt/notification/device/LoggingPushEndpointGateway.java)
- [PushEndpointGateway.java](../../notification-service/src/main/java/com/fcourt/notification/device/PushEndpointGateway.java)
- [SnsConfiguration.java](../../notification-service/src/main/java/com/fcourt/notification/device/SnsConfiguration.java)
- [SnsPushEndpointGateway.java](../../notification-service/src/main/java/com/fcourt/notification/device/SnsPushEndpointGateway.java)
- [UserDeviceRepository.java](../../notification-service/src/main/java/com/fcourt/notification/device/UserDeviceRepository.java)

### `com/fcourt/notification/event`

- [BusinessKeys.java](../../notification-service/src/main/java/com/fcourt/notification/event/BusinessKeys.java)
- [EmailPlan.java](../../notification-service/src/main/java/com/fcourt/notification/event/EmailPlan.java)
- [EmailPlanner.java](../../notification-service/src/main/java/com/fcourt/notification/event/EmailPlanner.java)
- [EventEnvelope.java](../../notification-service/src/main/java/com/fcourt/notification/event/EventEnvelope.java)
- [PoisonEventException.java](../../notification-service/src/main/java/com/fcourt/notification/event/PoisonEventException.java)

### `com/fcourt/notification/exception`

- [GlobalExceptionHandler.java](../../notification-service/src/main/java/com/fcourt/notification/exception/GlobalExceptionHandler.java)

### `com/fcourt/notification/inapp`

- [BranchDirectory.java](../../notification-service/src/main/java/com/fcourt/notification/inapp/BranchDirectory.java)
- [HttpBranchDirectory.java](../../notification-service/src/main/java/com/fcourt/notification/inapp/HttpBranchDirectory.java)
- [InAppEventHandler.java](../../notification-service/src/main/java/com/fcourt/notification/inapp/InAppEventHandler.java)
- [InAppNotification.java](../../notification-service/src/main/java/com/fcourt/notification/inapp/InAppNotification.java)
- [InAppNotificationController.java](../../notification-service/src/main/java/com/fcourt/notification/inapp/InAppNotificationController.java)
- [InAppNotificationRepository.java](../../notification-service/src/main/java/com/fcourt/notification/inapp/InAppNotificationRepository.java)
- [InAppPlan.java](../../notification-service/src/main/java/com/fcourt/notification/inapp/InAppPlan.java)
- [InAppPlanner.java](../../notification-service/src/main/java/com/fcourt/notification/inapp/InAppPlanner.java)

### `com/fcourt/notification/inbox`

- [EmailEventHandler.java](../../notification-service/src/main/java/com/fcourt/notification/inbox/EmailEventHandler.java)
- [NotificationService.java](../../notification-service/src/main/java/com/fcourt/notification/inbox/NotificationService.java)
- [ProcessedEventRepository.java](../../notification-service/src/main/java/com/fcourt/notification/inbox/ProcessedEventRepository.java)

### `com/fcourt/notification/kafka`

- [KafkaErrorHandlingConfiguration.java](../../notification-service/src/main/java/com/fcourt/notification/kafka/KafkaErrorHandlingConfiguration.java)
- [NotificationListener.java](../../notification-service/src/main/java/com/fcourt/notification/kafka/NotificationListener.java)
- [NotificationTopics.java](../../notification-service/src/main/java/com/fcourt/notification/kafka/NotificationTopics.java)
- [RedactingDeadLetterPublishingRecoverer.java](../../notification-service/src/main/java/com/fcourt/notification/kafka/RedactingDeadLetterPublishingRecoverer.java)

### `com/fcourt/notification/mail`

- [EmailDeliveryException.java](../../notification-service/src/main/java/com/fcourt/notification/mail/EmailDeliveryException.java)
- [EmailSender.java](../../notification-service/src/main/java/com/fcourt/notification/mail/EmailSender.java)
- [OutgoingEmail.java](../../notification-service/src/main/java/com/fcourt/notification/mail/OutgoingEmail.java)
- [SmtpEmailSender.java](../../notification-service/src/main/java/com/fcourt/notification/mail/SmtpEmailSender.java)
- [TemplateRenderer.java](../../notification-service/src/main/java/com/fcourt/notification/mail/TemplateRenderer.java)

### `com/fcourt/notification/push`

- [LoggingPushSender.java](../../notification-service/src/main/java/com/fcourt/notification/push/LoggingPushSender.java)
- [PushDeliveryService.java](../../notification-service/src/main/java/com/fcourt/notification/push/PushDeliveryService.java)
- [PushDeviceDelivery.java](../../notification-service/src/main/java/com/fcourt/notification/push/PushDeviceDelivery.java)
- [PushMessage.java](../../notification-service/src/main/java/com/fcourt/notification/push/PushMessage.java)
- [PushPlan.java](../../notification-service/src/main/java/com/fcourt/notification/push/PushPlan.java)
- [PushPlanner.java](../../notification-service/src/main/java/com/fcourt/notification/push/PushPlanner.java)
- [PushSender.java](../../notification-service/src/main/java/com/fcourt/notification/push/PushSender.java)
- [SnsPushSender.java](../../notification-service/src/main/java/com/fcourt/notification/push/SnsPushSender.java)

### `com/fcourt/notification/reminder`

- [LoggingReminderTrigger.java](../../notification-service/src/main/java/com/fcourt/notification/reminder/LoggingReminderTrigger.java)
- [ReminderAwsConfiguration.java](../../notification-service/src/main/java/com/fcourt/notification/reminder/ReminderAwsConfiguration.java)
- [ReminderProjection.java](../../notification-service/src/main/java/com/fcourt/notification/reminder/ReminderProjection.java)
- [ReminderScheduleProjection.java](../../notification-service/src/main/java/com/fcourt/notification/reminder/ReminderScheduleProjection.java)
- [ReminderScheduler.java](../../notification-service/src/main/java/com/fcourt/notification/reminder/ReminderScheduler.java)
- [ReminderTrigger.java](../../notification-service/src/main/java/com/fcourt/notification/reminder/ReminderTrigger.java)
- [ReminderWorker.java](../../notification-service/src/main/java/com/fcourt/notification/reminder/ReminderWorker.java)
- [SchedulerReminderTrigger.java](../../notification-service/src/main/java/com/fcourt/notification/reminder/SchedulerReminderTrigger.java)

## Protocol definitions

For REST behavior, begin with controller classes in the source map and the existing service documentation; this inventory does not infer endpoints from class names.

No `.proto` files are tracked in this service directory. This does not imply that the service makes no calls to other services.

## Test inventory

Test sources and fixtures listed here are navigation aids, not a report of passing tests. Review the test setup for Docker, Testcontainers, external services, and profile requirements before execution.

- [NotificationFlowIntegrationTest.java](../../notification-service/src/test/java/com/fcourt/notification/NotificationFlowIntegrationTest.java)
- [OpenApiDocsTest.java](../../notification-service/src/test/java/com/fcourt/notification/OpenApiDocsTest.java)
- [DeviceControllerIntegrationTest.java](../../notification-service/src/test/java/com/fcourt/notification/device/DeviceControllerIntegrationTest.java)
- [DeviceServiceTest.java](../../notification-service/src/test/java/com/fcourt/notification/device/DeviceServiceTest.java)
- [EmailPlannerTest.java](../../notification-service/src/test/java/com/fcourt/notification/event/EmailPlannerTest.java)
- [GlobalExceptionHandlerTest.java](../../notification-service/src/test/java/com/fcourt/notification/exception/GlobalExceptionHandlerTest.java)
- [HttpBranchDirectoryTest.java](../../notification-service/src/test/java/com/fcourt/notification/inapp/HttpBranchDirectoryTest.java)
- [InAppEventHandlerTest.java](../../notification-service/src/test/java/com/fcourt/notification/inapp/InAppEventHandlerTest.java)
- [InAppNotificationIntegrationTest.java](../../notification-service/src/test/java/com/fcourt/notification/inapp/InAppNotificationIntegrationTest.java)
- [InAppPlannerTest.java](../../notification-service/src/test/java/com/fcourt/notification/inapp/InAppPlannerTest.java)
- [NotificationServiceTest.java](../../notification-service/src/test/java/com/fcourt/notification/inbox/NotificationServiceTest.java)
- [KafkaErrorHandlingConfigurationTest.java](../../notification-service/src/test/java/com/fcourt/notification/kafka/KafkaErrorHandlingConfigurationTest.java)
- [NotificationTopicsTest.java](../../notification-service/src/test/java/com/fcourt/notification/kafka/NotificationTopicsTest.java)
- [RedactingDeadLetterPublishingRecovererTest.java](../../notification-service/src/test/java/com/fcourt/notification/kafka/RedactingDeadLetterPublishingRecovererTest.java)
- [SmtpEmailSenderTest.java](../../notification-service/src/test/java/com/fcourt/notification/mail/SmtpEmailSenderTest.java)
- [TemplateRendererTest.java](../../notification-service/src/test/java/com/fcourt/notification/mail/TemplateRendererTest.java)
- [PushDeliveryServiceTest.java](../../notification-service/src/test/java/com/fcourt/notification/push/PushDeliveryServiceTest.java)
- [PushPlannerTest.java](../../notification-service/src/test/java/com/fcourt/notification/push/PushPlannerTest.java)
- [SnsPushSenderTest.java](../../notification-service/src/test/java/com/fcourt/notification/push/SnsPushSenderTest.java)
- [ReminderProjectionTest.java](../../notification-service/src/test/java/com/fcourt/notification/reminder/ReminderProjectionTest.java)
- [ReminderScheduleProjectionTest.java](../../notification-service/src/test/java/com/fcourt/notification/reminder/ReminderScheduleProjectionTest.java)
- [ReminderSchedulerTest.java](../../notification-service/src/test/java/com/fcourt/notification/reminder/ReminderSchedulerTest.java)
- [ReminderWorkerTest.java](../../notification-service/src/test/java/com/fcourt/notification/reminder/ReminderWorkerTest.java)

From the repository root, the standard Maven verification entry point is `mvn -B -f notification-service/pom.xml verify`. Check the descriptor and environment requirements first.

## Database scripts and migrations

Review script contents and application configuration to distinguish initialization scripts from migrations loaded at runtime. Do not infer execution order across these two locations, or execute scripts against a shared database merely because they are listed here.

- [.gitkeep](../../notification-service/database/.gitkeep)
- [V1__processed_events.sql](../../notification-service/src/main/resources/db/migration/V1__processed_events.sql)
- [V2__booking_reminders.sql](../../notification-service/src/main/resources/db/migration/V2__booking_reminders.sql)
- [V3__user_devices.sql](../../notification-service/src/main/resources/db/migration/V3__user_devices.sql)
- [V4__push_delivery_and_reminders.sql](../../notification-service/src/main/resources/db/migration/V4__push_delivery_and_reminders.sql)
- [V5__in_app_notifications.sql](../../notification-service/src/main/resources/db/migration/V5__in_app_notifications.sql)

## Related documentation

Read the existing service documentation for behavior, setup instructions, and recorded validation details. Historical validation notes describe the run they record and are not fresh verification results.

- [PUSH_REMINDERS.md](../../notification-service/PUSH_REMINDERS.md)
- [README.md](../../notification-service/README.md)

For shared runtime setup, see [Docker Compose operations](../operations/docker-compose.md). For request routing, see [gateway operations](../operations/gateway.md).
