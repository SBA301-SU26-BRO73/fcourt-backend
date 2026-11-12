# reporting-service: repository reference

[Documentation index](../README.md) | [Service reference index](README.md)

This reference maps tracked files to their purpose for repository navigation. It describes file locations, not evidence that the service has been built, tested, or deployed.

## Service directory

The service lives in [`reporting-service/`](../../reporting-service/). Its build descriptor is [`pom.xml`](../../reporting-service/pom.xml). Paths below are relative to the repository root. Generated output and untracked local configuration are excluded.

## Build and container entry points

Inspect the Maven descriptor for dependencies and plugins, and the Dockerfile or Compose descriptor when present for container behavior. These links identify definitions; they do not execute them.

- [Dockerfile](../../reporting-service/Dockerfile)
- [mvnw](../../reporting-service/mvnw)
- [mvnw.cmd](../../reporting-service/mvnw.cmd)
- [pom.xml](../../reporting-service/pom.xml)

## Application resources

Tracked application resources are listed below. Inspect property placeholders and profile selection before choosing local values; do not copy credentials into documentation.

- [application.yml](../../reporting-service/src/main/resources/application.yml)

## Java source map

Java files are grouped by their tracked source directory. Package location alone does not establish an API contract or runtime dependency.

### `com/fcourt/reporting`

- [ReportingApplication.java](../../reporting-service/src/main/java/com/fcourt/reporting/ReportingApplication.java)

### `com/fcourt/reporting/api`

- [AnalyticsController.java](../../reporting-service/src/main/java/com/fcourt/reporting/api/AnalyticsController.java)
- [ApiErrors.java](../../reporting-service/src/main/java/com/fcourt/reporting/api/ApiErrors.java)
- [DashboardController.java](../../reporting-service/src/main/java/com/fcourt/reporting/api/DashboardController.java)
- [ExportController.java](../../reporting-service/src/main/java/com/fcourt/reporting/api/ExportController.java)
- [SuperAdminDashboardController.java](../../reporting-service/src/main/java/com/fcourt/reporting/api/SuperAdminDashboardController.java)

### `com/fcourt/reporting/client`

- [FacilityFeignClient.java](../../reporting-service/src/main/java/com/fcourt/reporting/client/FacilityFeignClient.java)

### `com/fcourt/reporting/config`

- [ClockConfiguration.java](../../reporting-service/src/main/java/com/fcourt/reporting/config/ClockConfiguration.java)
- [FacilityResilienceConfig.java](../../reporting-service/src/main/java/com/fcourt/reporting/config/FacilityResilienceConfig.java)
- [FacilityResilienceProperties.java](../../reporting-service/src/main/java/com/fcourt/reporting/config/FacilityResilienceProperties.java)
- [FeignClientsConfig.java](../../reporting-service/src/main/java/com/fcourt/reporting/config/FeignClientsConfig.java)
- [KafkaErrorHandling.java](../../reporting-service/src/main/java/com/fcourt/reporting/config/KafkaErrorHandling.java)
- [OpenApiConfig.java](../../reporting-service/src/main/java/com/fcourt/reporting/config/OpenApiConfig.java)
- [ReportingSecurity.java](../../reporting-service/src/main/java/com/fcourt/reporting/config/ReportingSecurity.java)

### `com/fcourt/reporting/events`

- [EventProjector.java](../../reporting-service/src/main/java/com/fcourt/reporting/events/EventProjector.java)
- [EventTranslator.java](../../reporting-service/src/main/java/com/fcourt/reporting/events/EventTranslator.java)
- [InvalidEventException.java](../../reporting-service/src/main/java/com/fcourt/reporting/events/InvalidEventException.java)
- [ReportingListeners.java](../../reporting-service/src/main/java/com/fcourt/reporting/events/ReportingListeners.java)

### `com/fcourt/reporting/integration`

- [FacilityClient.java](../../reporting-service/src/main/java/com/fcourt/reporting/integration/FacilityClient.java)
- [PlatformStatsClient.java](../../reporting-service/src/main/java/com/fcourt/reporting/integration/PlatformStatsClient.java)

### `com/fcourt/reporting/persistence`

- [ReadModelStore.java](../../reporting-service/src/main/java/com/fcourt/reporting/persistence/ReadModelStore.java)
- [ReportQueryStore.java](../../reporting-service/src/main/java/com/fcourt/reporting/persistence/ReportQueryStore.java)

### `com/fcourt/reporting/service`

- [AnalyticsService.java](../../reporting-service/src/main/java/com/fcourt/reporting/service/AnalyticsService.java)
- [DashboardService.java](../../reporting-service/src/main/java/com/fcourt/reporting/service/DashboardService.java)
- [RevenueExportService.java](../../reporting-service/src/main/java/com/fcourt/reporting/service/RevenueExportService.java)
- [RevenueReport.java](../../reporting-service/src/main/java/com/fcourt/reporting/service/RevenueReport.java)
- [SuperAdminDetailsService.java](../../reporting-service/src/main/java/com/fcourt/reporting/service/SuperAdminDetailsService.java)
- [SuperAdminOverviewService.java](../../reporting-service/src/main/java/com/fcourt/reporting/service/SuperAdminOverviewService.java)

## Protocol definitions

For REST behavior, begin with controller classes in the source map and the existing service documentation; this inventory does not infer endpoints from class names.

No `.proto` files are tracked in this service directory. This does not imply that the service makes no calls to other services.
