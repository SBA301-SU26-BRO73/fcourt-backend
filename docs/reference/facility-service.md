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
