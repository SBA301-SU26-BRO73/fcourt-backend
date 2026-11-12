# subscription-service: repository reference

[Documentation index](../README.md) | [Service reference index](README.md)

This reference maps tracked files to their purpose for repository navigation. It describes file locations, not evidence that the service has been built, tested, or deployed.

## Service directory

The service lives in [`subscription-service/`](../../subscription-service/). Its build descriptor is [`pom.xml`](../../subscription-service/pom.xml). Paths below are relative to the repository root. Generated output and untracked local configuration are excluded.

## Build and container entry points

Inspect the Maven descriptor for dependencies and plugins, and the Dockerfile or Compose descriptor when present for container behavior. These links identify definitions; they do not execute them.

- [Dockerfile](../../subscription-service/Dockerfile)
- [mvnw](../../subscription-service/mvnw)
- [mvnw.cmd](../../subscription-service/mvnw.cmd)
- [pom.xml](../../subscription-service/pom.xml)

## Application resources

Tracked application resources are listed below. Inspect property placeholders and profile selection before choosing local values; do not copy credentials into documentation.

- [application.yml](../../subscription-service/src/main/resources/application.yml)

## Java source map

Java files are grouped by their tracked source directory. Package location alone does not establish an API contract or runtime dependency.

### `com/fcourt/subscription`

- [SubscriptionApplication.java](../../subscription-service/src/main/java/com/fcourt/subscription/SubscriptionApplication.java)

### `com/fcourt/subscription/billing`

- [BillingController.java](../../subscription-service/src/main/java/com/fcourt/subscription/billing/BillingController.java)
- [BillingCycle.java](../../subscription-service/src/main/java/com/fcourt/subscription/billing/BillingCycle.java)
- [BillingDtos.java](../../subscription-service/src/main/java/com/fcourt/subscription/billing/BillingDtos.java)
- [FacilityUsageClient.java](../../subscription-service/src/main/java/com/fcourt/subscription/billing/FacilityUsageClient.java)
- [InternalLimitsController.java](../../subscription-service/src/main/java/com/fcourt/subscription/billing/InternalLimitsController.java)
- [PaymentRepository.java](../../subscription-service/src/main/java/com/fcourt/subscription/billing/PaymentRepository.java)
- [PaymentService.java](../../subscription-service/src/main/java/com/fcourt/subscription/billing/PaymentService.java)
- [SubscriptionRepository.java](../../subscription-service/src/main/java/com/fcourt/subscription/billing/SubscriptionRepository.java)
- [SubscriptionService.java](../../subscription-service/src/main/java/com/fcourt/subscription/billing/SubscriptionService.java)
- [WebhookRejectedException.java](../../subscription-service/src/main/java/com/fcourt/subscription/billing/WebhookRejectedException.java)

### `com/fcourt/subscription/common`

- [ApiException.java](../../subscription-service/src/main/java/com/fcourt/subscription/common/ApiException.java)
- [ApiExceptionHandler.java](../../subscription-service/src/main/java/com/fcourt/subscription/common/ApiExceptionHandler.java)
- [PageResponse.java](../../subscription-service/src/main/java/com/fcourt/subscription/common/PageResponse.java)
- [ProblemResponses.java](../../subscription-service/src/main/java/com/fcourt/subscription/common/ProblemResponses.java)
- [Role.java](../../subscription-service/src/main/java/com/fcourt/subscription/common/Role.java)

### `com/fcourt/subscription/config`

- [ClockConfiguration.java](../../subscription-service/src/main/java/com/fcourt/subscription/config/ClockConfiguration.java)
- [OpenApiConfig.java](../../subscription-service/src/main/java/com/fcourt/subscription/config/OpenApiConfig.java)
- [SecurityConfiguration.java](../../subscription-service/src/main/java/com/fcourt/subscription/config/SecurityConfiguration.java)

### `com/fcourt/subscription/courttype`

- [CourtTypeController.java](../../subscription-service/src/main/java/com/fcourt/subscription/courttype/CourtTypeController.java)
- [CourtTypeDtos.java](../../subscription-service/src/main/java/com/fcourt/subscription/courttype/CourtTypeDtos.java)
- [CourtTypeRepository.java](../../subscription-service/src/main/java/com/fcourt/subscription/courttype/CourtTypeRepository.java)
- [CourtTypeService.java](../../subscription-service/src/main/java/com/fcourt/subscription/courttype/CourtTypeService.java)

### `com/fcourt/subscription/payos`

- [PayosClient.java](../../subscription-service/src/main/java/com/fcourt/subscription/payos/PayosClient.java)
- [PayosConfiguration.java](../../subscription-service/src/main/java/com/fcourt/subscription/payos/PayosConfiguration.java)
- [PayosProperties.java](../../subscription-service/src/main/java/com/fcourt/subscription/payos/PayosProperties.java)
- [PayosSignature.java](../../subscription-service/src/main/java/com/fcourt/subscription/payos/PayosSignature.java)
- [RestPayosClient.java](../../subscription-service/src/main/java/com/fcourt/subscription/payos/RestPayosClient.java)

### `com/fcourt/subscription/plan`

- [PlanController.java](../../subscription-service/src/main/java/com/fcourt/subscription/plan/PlanController.java)
- [PlanDtos.java](../../subscription-service/src/main/java/com/fcourt/subscription/plan/PlanDtos.java)
- [PlanRepository.java](../../subscription-service/src/main/java/com/fcourt/subscription/plan/PlanRepository.java)
- [PlanService.java](../../subscription-service/src/main/java/com/fcourt/subscription/plan/PlanService.java)

### `com/fcourt/subscription/stats`

- [SaasRevenueController.java](../../subscription-service/src/main/java/com/fcourt/subscription/stats/SaasRevenueController.java)
- [SaasRevenueStats.java](../../subscription-service/src/main/java/com/fcourt/subscription/stats/SaasRevenueStats.java)
- [SubscriptionListStats.java](../../subscription-service/src/main/java/com/fcourt/subscription/stats/SubscriptionListStats.java)

## Protocol definitions

For REST behavior, begin with controller classes in the source map and the existing service documentation; this inventory does not infer endpoints from class names.

No `.proto` files are tracked in this service directory. This does not imply that the service makes no calls to other services.

## Test inventory

Test sources and fixtures listed here are navigation aids, not a report of passing tests. Review the test setup for Docker, Testcontainers, external services, and profile requirements before execution.

- [CatalogApiIntegrationTest.java](../../subscription-service/src/test/java/com/fcourt/subscription/CatalogApiIntegrationTest.java)
- [IntegrationSupport.java](../../subscription-service/src/test/java/com/fcourt/subscription/IntegrationSupport.java)
- [OpenApiDocsTest.java](../../subscription-service/src/test/java/com/fcourt/subscription/OpenApiDocsTest.java)
- [BillingApiIntegrationTest.java](../../subscription-service/src/test/java/com/fcourt/subscription/billing/BillingApiIntegrationTest.java)
- [BillingControllerTest.java](../../subscription-service/src/test/java/com/fcourt/subscription/billing/BillingControllerTest.java)
- [BillingCycleTest.java](../../subscription-service/src/test/java/com/fcourt/subscription/billing/BillingCycleTest.java)
- [FacilityUsageClientTest.java](../../subscription-service/src/test/java/com/fcourt/subscription/billing/FacilityUsageClientTest.java)
- [InternalLimitsControllerTest.java](../../subscription-service/src/test/java/com/fcourt/subscription/billing/InternalLimitsControllerTest.java)
- [PaymentServiceTest.java](../../subscription-service/src/test/java/com/fcourt/subscription/billing/PaymentServiceTest.java)
- [SubscriptionServiceTest.java](../../subscription-service/src/test/java/com/fcourt/subscription/billing/SubscriptionServiceTest.java)
- [WebhookFixtures.java](../../subscription-service/src/test/java/com/fcourt/subscription/billing/WebhookFixtures.java)
- [PageResponseTest.java](../../subscription-service/src/test/java/com/fcourt/subscription/common/PageResponseTest.java)
- [SecurityConfigurationTest.java](../../subscription-service/src/test/java/com/fcourt/subscription/config/SecurityConfigurationTest.java)
- [CourtTypeControllerTest.java](../../subscription-service/src/test/java/com/fcourt/subscription/courttype/CourtTypeControllerTest.java)
- [CourtTypeServiceTest.java](../../subscription-service/src/test/java/com/fcourt/subscription/courttype/CourtTypeServiceTest.java)
- [PayosPropertiesTest.java](../../subscription-service/src/test/java/com/fcourt/subscription/payos/PayosPropertiesTest.java)
- [PayosSignatureTest.java](../../subscription-service/src/test/java/com/fcourt/subscription/payos/PayosSignatureTest.java)
- [RestPayosClientTest.java](../../subscription-service/src/test/java/com/fcourt/subscription/payos/RestPayosClientTest.java)
- [PlanControllerTest.java](../../subscription-service/src/test/java/com/fcourt/subscription/plan/PlanControllerTest.java)
- [PlanServiceTest.java](../../subscription-service/src/test/java/com/fcourt/subscription/plan/PlanServiceTest.java)
- [SaasRevenueControllerTest.java](../../subscription-service/src/test/java/com/fcourt/subscription/stats/SaasRevenueControllerTest.java)
- [SaasRevenueStatsIntegrationTest.java](../../subscription-service/src/test/java/com/fcourt/subscription/stats/SaasRevenueStatsIntegrationTest.java)
- [application.properties](../../subscription-service/src/test/resources/application.properties)

From the repository root, the standard Maven verification entry point is `mvn -B -f subscription-service/pom.xml verify`. Check the descriptor and environment requirements first.

## Database scripts and migrations

Review script contents and application configuration to distinguish initialization scripts from migrations loaded at runtime. Do not infer execution order across these two locations, or execute scripts against a shared database merely because they are listed here.

- [V1__init_schema.sql](../../subscription-service/database/V1__init_schema.sql)
- [V2__seed_sample_data.sql](../../subscription-service/database/V2__seed_sample_data.sql)
- [V1__init_schema.sql](../../subscription-service/src/main/resources/db/migration/V1__init_schema.sql)
- [V2__seed_sample_data.sql](../../subscription-service/src/main/resources/db/migration/V2__seed_sample_data.sql)
- [V3__case_insensitive_names_and_positive_prices.sql](../../subscription-service/src/main/resources/db/migration/V3__case_insensitive_names_and_positive_prices.sql)
- [V4__subscription_starter_flag_and_dates.sql](../../subscription-service/src/main/resources/db/migration/V4__subscription_starter_flag_and_dates.sql)
