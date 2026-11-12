# staff-operations-service: repository reference

[Documentation index](../README.md) | [Service reference index](README.md)

This reference maps tracked files to their purpose for repository navigation. It describes file locations, not evidence that the service has been built, tested, or deployed.

## Service directory

The service lives in [`staff-operations-service/`](../../staff-operations-service/). Its build descriptor is [`pom.xml`](../../staff-operations-service/pom.xml). Paths below are relative to the repository root. Generated output and untracked local configuration are excluded.

## Build and container entry points

Inspect the Maven descriptor for dependencies and plugins, and the Dockerfile or Compose descriptor when present for container behavior. These links identify definitions; they do not execute them.

- [Dockerfile](../../staff-operations-service/Dockerfile)
- [compose.yml](../../staff-operations-service/compose.yml)
- [pom.xml](../../staff-operations-service/pom.xml)

## Application resources

Tracked application resources are listed below. Inspect property placeholders and profile selection before choosing local values; do not copy credentials into documentation.

- [application.yml](../../staff-operations-service/src/main/resources/application.yml)

## Java source map

Java files are grouped by their tracked source directory. Package location alone does not establish an API contract or runtime dependency.

### `com/fcourt/staff`

- [StaffApplication.java](../../staff-operations-service/src/main/java/com/fcourt/staff/StaffApplication.java)

### `com/fcourt/staff/api`

- [CheckInController.java](../../staff-operations-service/src/main/java/com/fcourt/staff/api/CheckInController.java)
- [OfflineCheckInController.java](../../staff-operations-service/src/main/java/com/fcourt/staff/api/OfflineCheckInController.java)
- [StaffController.java](../../staff-operations-service/src/main/java/com/fcourt/staff/api/StaffController.java)
- [StaffDirectoryController.java](../../staff-operations-service/src/main/java/com/fcourt/staff/api/StaffDirectoryController.java)
- [StaffErrors.java](../../staff-operations-service/src/main/java/com/fcourt/staff/api/StaffErrors.java)
- [StaffRequests.java](../../staff-operations-service/src/main/java/com/fcourt/staff/api/StaffRequests.java)
- [StaffView.java](../../staff-operations-service/src/main/java/com/fcourt/staff/api/StaffView.java)
- [WalkInController.java](../../staff-operations-service/src/main/java/com/fcourt/staff/api/WalkInController.java)
- [WeekScheduleController.java](../../staff-operations-service/src/main/java/com/fcourt/staff/api/WeekScheduleController.java)
- [WeekScheduleView.java](../../staff-operations-service/src/main/java/com/fcourt/staff/api/WeekScheduleView.java)

### `com/fcourt/staff/config`

- [OpenApiConfig.java](../../staff-operations-service/src/main/java/com/fcourt/staff/config/OpenApiConfig.java)
- [StaffConfiguration.java](../../staff-operations-service/src/main/java/com/fcourt/staff/config/StaffConfiguration.java)
- [StaffSecurity.java](../../staff-operations-service/src/main/java/com/fcourt/staff/config/StaffSecurity.java)

### `com/fcourt/staff/integration`

- [AvailabilityGateway.java](../../staff-operations-service/src/main/java/com/fcourt/staff/integration/AvailabilityGateway.java)
- [BookingGateway.java](../../staff-operations-service/src/main/java/com/fcourt/staff/integration/BookingGateway.java)
- [CheckInRejectedException.java](../../staff-operations-service/src/main/java/com/fcourt/staff/integration/CheckInRejectedException.java)
- [SlotSafetyGateway.java](../../staff-operations-service/src/main/java/com/fcourt/staff/integration/SlotSafetyGateway.java)
- [StaffOutboxRelay.java](../../staff-operations-service/src/main/java/com/fcourt/staff/integration/StaffOutboxRelay.java)
- [StaffUpstreams.java](../../staff-operations-service/src/main/java/com/fcourt/staff/integration/StaffUpstreams.java)

### `com/fcourt/staff/persistence`

- [BookingCommandStore.java](../../staff-operations-service/src/main/java/com/fcourt/staff/persistence/BookingCommandStore.java)
- [StaffStore.java](../../staff-operations-service/src/main/java/com/fcourt/staff/persistence/StaffStore.java)

### `com/fcourt/staff/service`

- [CheckInService.java](../../staff-operations-service/src/main/java/com/fcourt/staff/service/CheckInService.java)
- [OfflineCheckInService.java](../../staff-operations-service/src/main/java/com/fcourt/staff/service/OfflineCheckInService.java)
- [OfflinePermitCodec.java](../../staff-operations-service/src/main/java/com/fcourt/staff/service/OfflinePermitCodec.java)
- [ScheduleFlags.java](../../staff-operations-service/src/main/java/com/fcourt/staff/service/ScheduleFlags.java)
- [StaffService.java](../../staff-operations-service/src/main/java/com/fcourt/staff/service/StaffService.java)
- [WalkInPricing.java](../../staff-operations-service/src/main/java/com/fcourt/staff/service/WalkInPricing.java)
- [WalkInService.java](../../staff-operations-service/src/main/java/com/fcourt/staff/service/WalkInService.java)
- [WeekScheduleService.java](../../staff-operations-service/src/main/java/com/fcourt/staff/service/WeekScheduleService.java)

## Protocol definitions

For REST behavior, begin with controller classes in the source map and the existing service documentation; this inventory does not infer endpoints from class names.

Tracked Protocol Buffer definitions:

- [slot_safety.proto](../../staff-operations-service/src/main/proto/slot_safety.proto)

Consult the Maven descriptor for generation plugins before regenerating clients.

## Test inventory

Test sources and fixtures listed here are navigation aids, not a report of passing tests. Review the test setup for Docker, Testcontainers, external services, and profile requirements before execution.

- [AvailabilityGatewayTest.java](../../staff-operations-service/src/test/java/com/fcourt/staff/AvailabilityGatewayTest.java)
- [LocalClusterInfrastructure.java](../../staff-operations-service/src/test/java/com/fcourt/staff/LocalClusterInfrastructure.java)
- [LocalFixtureApplication.java](../../staff-operations-service/src/test/java/com/fcourt/staff/LocalFixtureApplication.java)
- [OfflinePermitTest.java](../../staff-operations-service/src/test/java/com/fcourt/staff/OfflinePermitTest.java)
- [OpenApiDocsTest.java](../../staff-operations-service/src/test/java/com/fcourt/staff/OpenApiDocsTest.java)
- [OutboxIntegrationTest.java](../../staff-operations-service/src/test/java/com/fcourt/staff/OutboxIntegrationTest.java)
- [ScheduleBoundaryTest.java](../../staff-operations-service/src/test/java/com/fcourt/staff/ScheduleBoundaryTest.java)
- [ScheduleFlagsTest.java](../../staff-operations-service/src/test/java/com/fcourt/staff/ScheduleFlagsTest.java)
- [ShiftValidationTest.java](../../staff-operations-service/src/test/java/com/fcourt/staff/ShiftValidationTest.java)
- [SlotSafetyFixture.java](../../staff-operations-service/src/test/java/com/fcourt/staff/SlotSafetyFixture.java)
- [SlotSafetyGatewayTest.java](../../staff-operations-service/src/test/java/com/fcourt/staff/SlotSafetyGatewayTest.java)
- [StaffBootstrapTest.java](../../staff-operations-service/src/test/java/com/fcourt/staff/StaffBootstrapTest.java)
- [StaffIntegrationTest.java](../../staff-operations-service/src/test/java/com/fcourt/staff/StaffIntegrationTest.java)
- [StaffUpstreamContractTest.java](../../staff-operations-service/src/test/java/com/fcourt/staff/StaffUpstreamContractTest.java)
- [WalkInPricingTest.java](../../staff-operations-service/src/test/java/com/fcourt/staff/WalkInPricingTest.java)
- [StaffDirectoryControllerTest.java](../../staff-operations-service/src/test/java/com/fcourt/staff/api/StaffDirectoryControllerTest.java)

From the repository root, the standard Maven verification entry point is `mvn -B -f staff-operations-service/pom.xml verify`. Check the descriptor and environment requirements first.

## Database scripts and migrations

Review script contents and application configuration to distinguish initialization scripts from migrations loaded at runtime. Do not infer execution order across these two locations, or execute scripts against a shared database merely because they are listed here.

- [V1__init_schema.sql](../../staff-operations-service/database/V1__init_schema.sql)
- [V2__seed_sample_data.sql](../../staff-operations-service/database/V2__seed_sample_data.sql)
- [V1__staff_schema.sql](../../staff-operations-service/src/main/resources/db/migration/V1__staff_schema.sql)
- [V2__staff_management.sql](../../staff-operations-service/src/main/resources/db/migration/V2__staff_management.sql)
- [V3__booking_commands_outbox.sql](../../staff-operations-service/src/main/resources/db/migration/V3__booking_commands_outbox.sql)
- [V4__check_in_audit.sql](../../staff-operations-service/src/main/resources/db/migration/V4__check_in_audit.sql)
- [V5__offline_check_in_channel.sql](../../staff-operations-service/src/main/resources/db/migration/V5__offline_check_in_channel.sql)
