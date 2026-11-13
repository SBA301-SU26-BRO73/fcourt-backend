# voucher-service: repository reference

[Documentation index](../README.md) | [Service reference index](README.md)

This reference maps tracked files to their purpose for repository navigation. It describes file locations, not evidence that the service has been built, tested, or deployed.

## Service directory

The service lives in [`voucher-service/`](../../voucher-service/). Its build descriptor is [`pom.xml`](../../voucher-service/pom.xml). Paths below are relative to the repository root. Generated output and untracked local configuration are excluded.

## Build and container entry points

Inspect the Maven descriptor for dependencies and plugins, and the Dockerfile or Compose descriptor when present for container behavior. These links identify definitions; they do not execute them.

- [Dockerfile](../../voucher-service/Dockerfile)
- [compose.yml](../../voucher-service/compose.yml)
- [mvnw](../../voucher-service/mvnw)
- [mvnw.cmd](../../voucher-service/mvnw.cmd)
- [pom.xml](../../voucher-service/pom.xml)

## Application resources

Tracked application resources are listed below. Inspect property placeholders and profile selection before choosing local values; do not copy credentials into documentation.

- [application.yml](../../voucher-service/src/main/resources/application.yml)

## Java source map

Java files are grouped by their tracked source directory. Package location alone does not establish an API contract or runtime dependency.

### `com/fcourt/voucher`

- [Voucher.java](../../voucher-service/src/main/java/com/fcourt/voucher/Voucher.java)
- [VoucherApplication.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherApplication.java)
- [VoucherApplyController.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherApplyController.java)
- [VoucherApplyRequest.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherApplyRequest.java)
- [VoucherApplyService.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherApplyService.java)
- [VoucherAuthority.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherAuthority.java)
- [VoucherBookingClient.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherBookingClient.java)
- [VoucherController.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherController.java)
- [VoucherGrpcServer.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherGrpcServer.java)
- [VoucherGrpcService.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherGrpcService.java)
- [VoucherPhone.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherPhone.java)
- [VoucherPricing.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherPricing.java)
- [VoucherRedemption.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherRedemption.java)
- [VoucherRedemptions.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherRedemptions.java)
- [VoucherRepository.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherRepository.java)
- [VoucherRequest.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherRequest.java)
- [VoucherReservationExpiry.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherReservationExpiry.java)
- [VoucherSecurity.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherSecurity.java)
- [VoucherService.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherService.java)
- [VoucherUsage.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherUsage.java)
- [VoucherUsageController.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherUsageController.java)
- [VoucherUsageEvents.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherUsageEvents.java)
- [VoucherUsageKafka.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherUsageKafka.java)
- [VoucherUsageRepository.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherUsageRepository.java)
- [VoucherUsageService.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherUsageService.java)
- [VoucherValidateRequest.java](../../voucher-service/src/main/java/com/fcourt/voucher/VoucherValidateRequest.java)

### `com/fcourt/voucher/client`

- [FacilityBranch.java](../../voucher-service/src/main/java/com/fcourt/voucher/client/FacilityBranch.java)
- [FacilityFeignClient.java](../../voucher-service/src/main/java/com/fcourt/voucher/client/FacilityFeignClient.java)

### `com/fcourt/voucher/config`

- [FacilityResilienceConfig.java](../../voucher-service/src/main/java/com/fcourt/voucher/config/FacilityResilienceConfig.java)
- [FacilityResilienceProperties.java](../../voucher-service/src/main/java/com/fcourt/voucher/config/FacilityResilienceProperties.java)
- [FeignClientsConfig.java](../../voucher-service/src/main/java/com/fcourt/voucher/config/FeignClientsConfig.java)
- [OpenApiConfig.java](../../voucher-service/src/main/java/com/fcourt/voucher/config/OpenApiConfig.java)

### `com/fcourt/voucher/exception`

- [GlobalExceptionHandler.java](../../voucher-service/src/main/java/com/fcourt/voucher/exception/GlobalExceptionHandler.java)

## Protocol definitions

For REST behavior, begin with controller classes in the source map and the existing service documentation; this inventory does not infer endpoints from class names.

Tracked Protocol Buffer definitions:

- [voucher_validation.proto](../../voucher-service/src/main/proto/voucher_validation.proto)

Consult the Maven descriptor for generation plugins before regenerating clients.

## Test inventory

Test sources and fixtures listed here are navigation aids, not a report of passing tests. Review the test setup for Docker, Testcontainers, external services, and profile requirements before execution.

- [OpenApiDocsTest.java](../../voucher-service/src/test/java/com/fcourt/voucher/OpenApiDocsTest.java)
- [VoucherApiTest.java](../../voucher-service/src/test/java/com/fcourt/voucher/VoucherApiTest.java)
- [VoucherApplyTest.java](../../voucher-service/src/test/java/com/fcourt/voucher/VoucherApplyTest.java)
- [VoucherAuthorityCircuitBreakerTest.java](../../voucher-service/src/test/java/com/fcourt/voucher/VoucherAuthorityCircuitBreakerTest.java)
- [VoucherAuthorityWireMockTest.java](../../voucher-service/src/test/java/com/fcourt/voucher/VoucherAuthorityWireMockTest.java)
- [VoucherGrpcServerTest.java](../../voucher-service/src/test/java/com/fcourt/voucher/VoucherGrpcServerTest.java)
- [VoucherPhoneTest.java](../../voucher-service/src/test/java/com/fcourt/voucher/VoucherPhoneTest.java)
- [VoucherPricingTest.java](../../voucher-service/src/test/java/com/fcourt/voucher/VoucherPricingTest.java)
- [VoucherUsageMigrationTest.java](../../voucher-service/src/test/java/com/fcourt/voucher/VoucherUsageMigrationTest.java)
- [GlobalExceptionHandlerTest.java](../../voucher-service/src/test/java/com/fcourt/voucher/exception/GlobalExceptionHandlerTest.java)

From the repository root, the standard Maven verification entry point is `mvn -B -f voucher-service/pom.xml verify`. Check the descriptor and environment requirements first.

## Database scripts and migrations

Review script contents and application configuration to distinguish initialization scripts from migrations loaded at runtime. Do not infer execution order across these two locations, or execute scripts against a shared database merely because they are listed here.

- [V1__init_schema.sql](../../voucher-service/database/V1__init_schema.sql)
- [V2__seed_sample_data.sql](../../voucher-service/database/V2__seed_sample_data.sql)
- [V1__vouchers.sql](../../voucher-service/src/main/resources/db/migration/V1__vouchers.sql)
- [V2__voucher_redemptions.sql](../../voucher-service/src/main/resources/db/migration/V2__voucher_redemptions.sql)
- [V3__voucher_usage_history.sql](../../voucher-service/src/main/resources/db/migration/V3__voucher_usage_history.sql)
- [V4__voucher_usage_customer_snapshot.sql](../../voucher-service/src/main/resources/db/migration/V4__voucher_usage_customer_snapshot.sql)

## Related documentation

Read the existing service documentation for behavior, setup instructions, and recorded validation details. Historical validation notes describe the run they record and are not fresh verification results.

This service has no tracked Markdown document in its directory. Start with the [repository README](../../README.md) and [documentation index](../README.md).

For shared runtime setup, see [Docker Compose operations](../operations/docker-compose.md). For request routing, see [gateway operations](../operations/gateway.md).
