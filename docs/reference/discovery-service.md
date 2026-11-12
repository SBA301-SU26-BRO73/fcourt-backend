# discovery-service: repository reference

[Documentation index](../README.md) | [Service reference index](README.md)

This reference maps tracked files to their purpose for repository navigation. It describes file locations, not evidence that the service has been built, tested, or deployed.

## Service directory

The service lives in [`discovery-service/`](../../discovery-service/). Its build descriptor is [`pom.xml`](../../discovery-service/pom.xml). Paths below are relative to the repository root. Generated output and untracked local configuration are excluded.

## Build and container entry points

Inspect the Maven descriptor for dependencies and plugins, and the Dockerfile or Compose descriptor when present for container behavior. These links identify definitions; they do not execute them.

- [Dockerfile](../../discovery-service/Dockerfile)
- [compose.yml](../../discovery-service/compose.yml)
- [mvnw](../../discovery-service/mvnw)
- [mvnw.cmd](../../discovery-service/mvnw.cmd)
- [pom.xml](../../discovery-service/pom.xml)

## Application resources

Tracked application resources are listed below. Inspect property placeholders and profile selection before choosing local values; do not copy credentials into documentation.

- [application.yml](../../discovery-service/src/main/resources/application.yml)

## Java source map

Java files are grouped by their tracked source directory. Package location alone does not establish an API contract or runtime dependency.

### `com/fcourt/discovery`

- [DiscoveryApplication.java](../../discovery-service/src/main/java/com/fcourt/discovery/DiscoveryApplication.java)

### `com/fcourt/discovery/component`

- [BranchEventListener.java](../../discovery-service/src/main/java/com/fcourt/discovery/component/BranchEventListener.java)
- [ImageStorage.java](../../discovery-service/src/main/java/com/fcourt/discovery/component/ImageStorage.java)
- [ReviewBookingClient.java](../../discovery-service/src/main/java/com/fcourt/discovery/component/ReviewBookingClient.java)
- [ReviewBranchAuthority.java](../../discovery-service/src/main/java/com/fcourt/discovery/component/ReviewBranchAuthority.java)
- [ReviewIdentity.java](../../discovery-service/src/main/java/com/fcourt/discovery/component/ReviewIdentity.java)
- [ReviewImageCleanup.java](../../discovery-service/src/main/java/com/fcourt/discovery/component/ReviewImageCleanup.java)
- [ReviewImagePolicy.java](../../discovery-service/src/main/java/com/fcourt/discovery/component/ReviewImagePolicy.java)
- [ReviewImageValidator.java](../../discovery-service/src/main/java/com/fcourt/discovery/component/ReviewImageValidator.java)
- [ReviewUploadLimiter.java](../../discovery-service/src/main/java/com/fcourt/discovery/component/ReviewUploadLimiter.java)
- [ReviewerNames.java](../../discovery-service/src/main/java/com/fcourt/discovery/component/ReviewerNames.java)
- [ReviewerProfileClient.java](../../discovery-service/src/main/java/com/fcourt/discovery/component/ReviewerProfileClient.java)
- [S3ImageStorage.java](../../discovery-service/src/main/java/com/fcourt/discovery/component/S3ImageStorage.java)
- [SlotSourceClient.java](../../discovery-service/src/main/java/com/fcourt/discovery/component/SlotSourceClient.java)

### `com/fcourt/discovery/config`

- [DiscoverySecurity.java](../../discovery-service/src/main/java/com/fcourt/discovery/config/DiscoverySecurity.java)
- [ImageStorageConfig.java](../../discovery-service/src/main/java/com/fcourt/discovery/config/ImageStorageConfig.java)
- [KafkaConfiguration.java](../../discovery-service/src/main/java/com/fcourt/discovery/config/KafkaConfiguration.java)
- [OpenApiConfig.java](../../discovery-service/src/main/java/com/fcourt/discovery/config/OpenApiConfig.java)
- [SlotClockConfiguration.java](../../discovery-service/src/main/java/com/fcourt/discovery/config/SlotClockConfiguration.java)

### `com/fcourt/discovery/controller`

- [BranchController.java](../../discovery-service/src/main/java/com/fcourt/discovery/controller/BranchController.java)
- [CourtSlotController.java](../../discovery-service/src/main/java/com/fcourt/discovery/controller/CourtSlotController.java)
- [CourtSlotInternalController.java](../../discovery-service/src/main/java/com/fcourt/discovery/controller/CourtSlotInternalController.java)
- [ReviewController.java](../../discovery-service/src/main/java/com/fcourt/discovery/controller/ReviewController.java)

### `com/fcourt/discovery/dto/request`

- [BranchSearchRequest.java](../../discovery-service/src/main/java/com/fcourt/discovery/dto/request/BranchSearchRequest.java)
- [CreateReviewRequest.java](../../discovery-service/src/main/java/com/fcourt/discovery/dto/request/CreateReviewRequest.java)
- [ReplyReviewRequest.java](../../discovery-service/src/main/java/com/fcourt/discovery/dto/request/ReplyReviewRequest.java)

### `com/fcourt/discovery/dto/response`

- [BlockingSlotResponse.java](../../discovery-service/src/main/java/com/fcourt/discovery/dto/response/BlockingSlotResponse.java)
- [BranchSearchResponse.java](../../discovery-service/src/main/java/com/fcourt/discovery/dto/response/BranchSearchResponse.java)
- [CourtSlotResponse.java](../../discovery-service/src/main/java/com/fcourt/discovery/dto/response/CourtSlotResponse.java)
- [ReviewPage.java](../../discovery-service/src/main/java/com/fcourt/discovery/dto/response/ReviewPage.java)

### `com/fcourt/discovery/exception`

- [ApiExceptionHandler.java](../../discovery-service/src/main/java/com/fcourt/discovery/exception/ApiExceptionHandler.java)

### `com/fcourt/discovery/model`

- [Review.java](../../discovery-service/src/main/java/com/fcourt/discovery/model/Review.java)

### `com/fcourt/discovery/model/event`

- [BranchProjectionChanged.java](../../discovery-service/src/main/java/com/fcourt/discovery/model/event/BranchProjectionChanged.java)
- [BranchUpdatedEvent.java](../../discovery-service/src/main/java/com/fcourt/discovery/model/event/BranchUpdatedEvent.java)

### `com/fcourt/discovery/repository`

- [BranchProjection.java](../../discovery-service/src/main/java/com/fcourt/discovery/repository/BranchProjection.java)
- [BranchSearchRepository.java](../../discovery-service/src/main/java/com/fcourt/discovery/repository/BranchSearchRepository.java)
- [ReviewRepository.java](../../discovery-service/src/main/java/com/fcourt/discovery/repository/ReviewRepository.java)
- [SlotHoldRepository.java](../../discovery-service/src/main/java/com/fcourt/discovery/repository/SlotHoldRepository.java)

### `com/fcourt/discovery/service`

- [BranchSearchService.java](../../discovery-service/src/main/java/com/fcourt/discovery/service/BranchSearchService.java)
- [CourtSlotService.java](../../discovery-service/src/main/java/com/fcourt/discovery/service/CourtSlotService.java)
- [ReviewImageService.java](../../discovery-service/src/main/java/com/fcourt/discovery/service/ReviewImageService.java)
- [ReviewService.java](../../discovery-service/src/main/java/com/fcourt/discovery/service/ReviewService.java)

### `com/fcourt/discovery/service/impl`

- [CourtSlotServiceImpl.java](../../discovery-service/src/main/java/com/fcourt/discovery/service/impl/CourtSlotServiceImpl.java)

## Protocol definitions

For REST behavior, begin with controller classes in the source map and the existing service documentation; this inventory does not infer endpoints from class names.

No `.proto` files are tracked in this service directory. This does not imply that the service makes no calls to other services.
