# facility-service: repository reference

[Documentation index](../README.md) | [Service reference index](README.md)

This reference maps tracked files to their purpose for repository navigation. It describes file locations, not evidence that the service has been built, tested, or deployed.

## Service directory

The service lives in [`facility-service/`](../../facility-service/). Its build descriptor is [`pom.xml`](../../facility-service/pom.xml). Paths below are relative to the repository root. Generated output and untracked local configuration are excluded.

## Build and container entry points

Inspect the Maven descriptor for dependencies and plugins, and the Dockerfile or Compose descriptor when present for container behavior. These links identify definitions; they do not execute them.

- [Dockerfile](../../facility-service/Dockerfile)
- [mvnw](../../facility-service/mvnw)
- [mvnw.cmd](../../facility-service/mvnw.cmd)
- [pom.xml](../../facility-service/pom.xml)

## Application resources

Tracked application resources are listed below. Inspect property placeholders and profile selection before choosing local values; do not copy credentials into documentation.

- [application.yml](../../facility-service/src/main/resources/application.yml)

## Java source map

Java files are grouped by their tracked source directory. Package location alone does not establish an API contract or runtime dependency.

### `com/fcourt/facility`

- [FacilityApplication.java](../../facility-service/src/main/java/com/fcourt/facility/FacilityApplication.java)

### `com/fcourt/facility/api`

- [ApiErrors.java](../../facility-service/src/main/java/com/fcourt/facility/api/ApiErrors.java)
- [BookingContextController.java](../../facility-service/src/main/java/com/fcourt/facility/api/BookingContextController.java)
- [BranchController.java](../../facility-service/src/main/java/com/fcourt/facility/api/BranchController.java)
- [BranchImageController.java](../../facility-service/src/main/java/com/fcourt/facility/api/BranchImageController.java)
- [BranchImageResponse.java](../../facility-service/src/main/java/com/fcourt/facility/api/BranchImageResponse.java)
- [BranchOwnerController.java](../../facility-service/src/main/java/com/fcourt/facility/api/BranchOwnerController.java)
- [BranchRequest.java](../../facility-service/src/main/java/com/fcourt/facility/api/BranchRequest.java)
- [BranchResponse.java](../../facility-service/src/main/java/com/fcourt/facility/api/BranchResponse.java)
- [CourtController.java](../../facility-service/src/main/java/com/fcourt/facility/api/CourtController.java)
- [CourtImageController.java](../../facility-service/src/main/java/com/fcourt/facility/api/CourtImageController.java)
- [CourtRequest.java](../../facility-service/src/main/java/com/fcourt/facility/api/CourtRequest.java)
- [CourtResponse.java](../../facility-service/src/main/java/com/fcourt/facility/api/CourtResponse.java)
- [LockController.java](../../facility-service/src/main/java/com/fcourt/facility/api/LockController.java)
- [LockDtos.java](../../facility-service/src/main/java/com/fcourt/facility/api/LockDtos.java)
- [Owners.java](../../facility-service/src/main/java/com/fcourt/facility/api/Owners.java)
- [PricingController.java](../../facility-service/src/main/java/com/fcourt/facility/api/PricingController.java)
- [PricingDtos.java](../../facility-service/src/main/java/com/fcourt/facility/api/PricingDtos.java)
- [PublicCourtResponse.java](../../facility-service/src/main/java/com/fcourt/facility/api/PublicCourtResponse.java)
- [ReservationController.java](../../facility-service/src/main/java/com/fcourt/facility/api/ReservationController.java)
- [ReservationDtos.java](../../facility-service/src/main/java/com/fcourt/facility/api/ReservationDtos.java)
- [TemplateController.java](../../facility-service/src/main/java/com/fcourt/facility/api/TemplateController.java)
- [TemplateDtos.java](../../facility-service/src/main/java/com/fcourt/facility/api/TemplateDtos.java)

### `com/fcourt/facility/config`

- [FacilitySecurity.java](../../facility-service/src/main/java/com/fcourt/facility/config/FacilitySecurity.java)
- [ImageStorageConfig.java](../../facility-service/src/main/java/com/fcourt/facility/config/ImageStorageConfig.java)
- [LockClockConfiguration.java](../../facility-service/src/main/java/com/fcourt/facility/config/LockClockConfiguration.java)
- [OpenApiConfig.java](../../facility-service/src/main/java/com/fcourt/facility/config/OpenApiConfig.java)

### `com/fcourt/facility/events`

- [BranchEventWriter.java](../../facility-service/src/main/java/com/fcourt/facility/events/BranchEventWriter.java)
- [BranchUpdatedEvent.java](../../facility-service/src/main/java/com/fcourt/facility/events/BranchUpdatedEvent.java)
- [EventsConfiguration.java](../../facility-service/src/main/java/com/fcourt/facility/events/EventsConfiguration.java)
- [LockScheduleEvents.java](../../facility-service/src/main/java/com/fcourt/facility/events/LockScheduleEvents.java)
- [OutboxRelay.java](../../facility-service/src/main/java/com/fcourt/facility/events/OutboxRelay.java)
- [RealtimeConfiguration.java](../../facility-service/src/main/java/com/fcourt/facility/events/RealtimeConfiguration.java)
- [RedisLockScheduleEvents.java](../../facility-service/src/main/java/com/fcourt/facility/events/RedisLockScheduleEvents.java)
- [ReservationRealtimeBridge.java](../../facility-service/src/main/java/com/fcourt/facility/events/ReservationRealtimeBridge.java)

### `com/fcourt/facility/integration`

- [S3ImageStorage.java](../../facility-service/src/main/java/com/fcourt/facility/integration/S3ImageStorage.java)
- [SlotAvailabilityClient.java](../../facility-service/src/main/java/com/fcourt/facility/integration/SlotAvailabilityClient.java)
- [SubscriptionLimitClient.java](../../facility-service/src/main/java/com/fcourt/facility/integration/SubscriptionLimitClient.java)

### `com/fcourt/facility/persistence`

- [BranchImageStore.java](../../facility-service/src/main/java/com/fcourt/facility/persistence/BranchImageStore.java)
- [BranchStore.java](../../facility-service/src/main/java/com/fcourt/facility/persistence/BranchStore.java)
- [CourtStore.java](../../facility-service/src/main/java/com/fcourt/facility/persistence/CourtStore.java)
- [LockStore.java](../../facility-service/src/main/java/com/fcourt/facility/persistence/LockStore.java)
- [PricingRuleStore.java](../../facility-service/src/main/java/com/fcourt/facility/persistence/PricingRuleStore.java)
- [ReservationStore.java](../../facility-service/src/main/java/com/fcourt/facility/persistence/ReservationStore.java)
- [TemplateStore.java](../../facility-service/src/main/java/com/fcourt/facility/persistence/TemplateStore.java)

### `com/fcourt/facility/service`

- [BranchImageService.java](../../facility-service/src/main/java/com/fcourt/facility/service/BranchImageService.java)
- [BranchService.java](../../facility-service/src/main/java/com/fcourt/facility/service/BranchService.java)
- [CourtImageService.java](../../facility-service/src/main/java/com/fcourt/facility/service/CourtImageService.java)
- [CourtService.java](../../facility-service/src/main/java/com/fcourt/facility/service/CourtService.java)
- [ImageStorage.java](../../facility-service/src/main/java/com/fcourt/facility/service/ImageStorage.java)
- [ImageUploadValidator.java](../../facility-service/src/main/java/com/fcourt/facility/service/ImageUploadValidator.java)
- [LockService.java](../../facility-service/src/main/java/com/fcourt/facility/service/LockService.java)
- [PricingEngine.java](../../facility-service/src/main/java/com/fcourt/facility/service/PricingEngine.java)
- [PricingService.java](../../facility-service/src/main/java/com/fcourt/facility/service/PricingService.java)
- [ReservationService.java](../../facility-service/src/main/java/com/fcourt/facility/service/ReservationService.java)
- [ReservationSweeper.java](../../facility-service/src/main/java/com/fcourt/facility/service/ReservationSweeper.java)
- [SlotTemplateEngine.java](../../facility-service/src/main/java/com/fcourt/facility/service/SlotTemplateEngine.java)
- [SlotWindows.java](../../facility-service/src/main/java/com/fcourt/facility/service/SlotWindows.java)
- [TemplateService.java](../../facility-service/src/main/java/com/fcourt/facility/service/TemplateService.java)

## Protocol definitions

For REST behavior, begin with controller classes in the source map and the existing service documentation; this inventory does not infer endpoints from class names.

No `.proto` files are tracked in this service directory. This does not imply that the service makes no calls to other services.

## Test inventory

Test sources and fixtures listed here are navigation aids, not a report of passing tests. Review the test setup for Docker, Testcontainers, external services, and profile requirements before execution.

- [BranchImageApiIntegrationTest.java](../../facility-service/src/test/java/com/fcourt/facility/BranchImageApiIntegrationTest.java)
- [CourtImageApiIntegrationTest.java](../../facility-service/src/test/java/com/fcourt/facility/CourtImageApiIntegrationTest.java)
- [CourtReservationIntegrationTest.java](../../facility-service/src/test/java/com/fcourt/facility/CourtReservationIntegrationTest.java)
- [FacilityApiIntegrationTest.java](../../facility-service/src/test/java/com/fcourt/facility/FacilityApiIntegrationTest.java)
- [OpenApiDocsTest.java](../../facility-service/src/test/java/com/fcourt/facility/OpenApiDocsTest.java)
- [PricingRulesIntegrationTest.java](../../facility-service/src/test/java/com/fcourt/facility/PricingRulesIntegrationTest.java)
- [BranchOwnerControllerTest.java](../../facility-service/src/test/java/com/fcourt/facility/api/BranchOwnerControllerTest.java)
- [BranchRequestValidationTest.java](../../facility-service/src/test/java/com/fcourt/facility/api/BranchRequestValidationTest.java)
- [BulkApplyRequestValidationTest.java](../../facility-service/src/test/java/com/fcourt/facility/api/BulkApplyRequestValidationTest.java)
- [LockRequestValidationTest.java](../../facility-service/src/test/java/com/fcourt/facility/api/LockRequestValidationTest.java)
- [PricingRequestValidationTest.java](../../facility-service/src/test/java/com/fcourt/facility/api/PricingRequestValidationTest.java)
- [ReservationControllerTest.java](../../facility-service/src/test/java/com/fcourt/facility/api/ReservationControllerTest.java)
- [TemplateRequestValidationTest.java](../../facility-service/src/test/java/com/fcourt/facility/api/TemplateRequestValidationTest.java)
- [RedisLockScheduleEventsTest.java](../../facility-service/src/test/java/com/fcourt/facility/events/RedisLockScheduleEventsTest.java)
- [ReservationRealtimeBridgeTest.java](../../facility-service/src/test/java/com/fcourt/facility/events/ReservationRealtimeBridgeTest.java)
- [S3ImageStorageContainerTest.java](../../facility-service/src/test/java/com/fcourt/facility/integration/S3ImageStorageContainerTest.java)
- [S3ImageStorageTest.java](../../facility-service/src/test/java/com/fcourt/facility/integration/S3ImageStorageTest.java)
- [CourtImageServiceTest.java](../../facility-service/src/test/java/com/fcourt/facility/service/CourtImageServiceTest.java)
- [CourtStatusEventsTest.java](../../facility-service/src/test/java/com/fcourt/facility/service/CourtStatusEventsTest.java)
- [ImageUploadValidatorTest.java](../../facility-service/src/test/java/com/fcourt/facility/service/ImageUploadValidatorTest.java)
- [LockRealtimeEventsTest.java](../../facility-service/src/test/java/com/fcourt/facility/service/LockRealtimeEventsTest.java)
- [LockServiceTest.java](../../facility-service/src/test/java/com/fcourt/facility/service/LockServiceTest.java)
- [PricingEngineTest.java](../../facility-service/src/test/java/com/fcourt/facility/service/PricingEngineTest.java)
- [PricingServiceValidationTest.java](../../facility-service/src/test/java/com/fcourt/facility/service/PricingServiceValidationTest.java)
- [ReservationServiceTest.java](../../facility-service/src/test/java/com/fcourt/facility/service/ReservationServiceTest.java)
- [SlotTemplateEngineTest.java](../../facility-service/src/test/java/com/fcourt/facility/service/SlotTemplateEngineTest.java)
- [SlotWindowsTest.java](../../facility-service/src/test/java/com/fcourt/facility/service/SlotWindowsTest.java)

From the repository root, the standard Maven verification entry point is `mvn -B -f facility-service/pom.xml verify`. Check the descriptor and environment requirements first.

## Database scripts and migrations

Review script contents and application configuration to distinguish initialization scripts from migrations loaded at runtime. Do not infer execution order across these two locations, or execute scripts against a shared database merely because they are listed here.

- [V1__init_schema.sql](../../facility-service/database/V1__init_schema.sql)
- [V2__seed_sample_data.sql](../../facility-service/database/V2__seed_sample_data.sql)
- [V1__init_schema.sql](../../facility-service/src/main/resources/db/migration/V1__init_schema.sql)
- [V2__seed_sample_data.sql](../../facility-service/src/main/resources/db/migration/V2__seed_sample_data.sql)
- [V30__court_pricing_rules.sql](../../facility-service/src/main/resources/db/migration/V30__court_pricing_rules.sql)
- [V3__facility_management.sql](../../facility-service/src/main/resources/db/migration/V3__facility_management.sql)
- [V40__court_locks.sql](../../facility-service/src/main/resources/db/migration/V40__court_locks.sql)
- [V4__branch_unique_name.sql](../../facility-service/src/main/resources/db/migration/V4__branch_unique_name.sql)
- [V50__court_reservations.sql](../../facility-service/src/main/resources/db/migration/V50__court_reservations.sql)
- [V70__branch_images.sql](../../facility-service/src/main/resources/db/migration/V70__branch_images.sql)
- [V71__backfill_branch_bank_bin.sql](../../facility-service/src/main/resources/db/migration/V71__backfill_branch_bank_bin.sql)

## Related documentation

Read the existing service documentation for behavior, setup instructions, and recorded validation details. Historical validation notes describe the run they record and are not fresh verification results.

- [README.md](../../facility-service/README.md)

For shared runtime setup, see [Docker Compose operations](../operations/docker-compose.md). For request routing, see [gateway operations](../operations/gateway.md).
