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
