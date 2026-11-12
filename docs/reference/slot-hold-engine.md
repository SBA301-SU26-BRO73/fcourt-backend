# slot-hold-engine: repository reference

[Documentation index](../README.md) | [Service reference index](README.md)

This reference maps tracked files to their purpose for repository navigation. It describes file locations, not evidence that the service has been built, tested, or deployed.

## Service directory

The service lives in [`slot-hold-engine/`](../../slot-hold-engine/). Its build descriptor is [`pom.xml`](../../slot-hold-engine/pom.xml). Paths below are relative to the repository root. Generated output and untracked local configuration are excluded.

## Build and container entry points

Inspect the Maven descriptor for dependencies and plugins, and the Dockerfile or Compose descriptor when present for container behavior. These links identify definitions; they do not execute them.

- [Dockerfile](../../slot-hold-engine/Dockerfile)
- [compose.yml](../../slot-hold-engine/compose.yml)
- [mvnw](../../slot-hold-engine/mvnw)
- [mvnw.cmd](../../slot-hold-engine/mvnw.cmd)
- [pom.xml](../../slot-hold-engine/pom.xml)

## Application resources

Tracked application resources are listed below. Inspect property placeholders and profile selection before choosing local values; do not copy credentials into documentation.

- [application.yml](../../slot-hold-engine/src/main/resources/application.yml)
- [acquire.lua](../../slot-hold-engine/src/main/resources/redis/acquire.lua)
- [release.lua](../../slot-hold-engine/src/main/resources/redis/release.lua)
- [sweep.lua](../../slot-hold-engine/src/main/resources/redis/sweep.lua)

## Java source map

Java files are grouped by their tracked source directory. Package location alone does not establish an API contract or runtime dependency.

### `com/fcourt/hold`

- [CounterHolds.java](../../slot-hold-engine/src/main/java/com/fcourt/hold/CounterHolds.java)
- [CounterSafetyServer.java](../../slot-hold-engine/src/main/java/com/fcourt/hold/CounterSafetyServer.java)
- [DiscoveryGateway.java](../../slot-hold-engine/src/main/java/com/fcourt/hold/DiscoveryGateway.java)
- [HoldApplication.java](../../slot-hold-engine/src/main/java/com/fcourt/hold/HoldApplication.java)
- [HoldConfiguration.java](../../slot-hold-engine/src/main/java/com/fcourt/hold/HoldConfiguration.java)
- [HoldController.java](../../slot-hold-engine/src/main/java/com/fcourt/hold/HoldController.java)
- [HoldExpirySweeper.java](../../slot-hold-engine/src/main/java/com/fcourt/hold/HoldExpirySweeper.java)
- [HoldRequest.java](../../slot-hold-engine/src/main/java/com/fcourt/hold/HoldRequest.java)
- [HoldResponse.java](../../slot-hold-engine/src/main/java/com/fcourt/hold/HoldResponse.java)
- [HoldService.java](../../slot-hold-engine/src/main/java/com/fcourt/hold/HoldService.java)
- [HoldStaffIdentity.java](../../slot-hold-engine/src/main/java/com/fcourt/hold/HoldStaffIdentity.java)
- [OnlineHolds.java](../../slot-hold-engine/src/main/java/com/fcourt/hold/OnlineHolds.java)
- [RedisHoldStore.java](../../slot-hold-engine/src/main/java/com/fcourt/hold/RedisHoldStore.java)

### `com/fcourt/hold/config`

- [OpenApiConfig.java](../../slot-hold-engine/src/main/java/com/fcourt/hold/config/OpenApiConfig.java)

### `com/fcourt/hold/exception`

- [GlobalExceptionHandler.java](../../slot-hold-engine/src/main/java/com/fcourt/hold/exception/GlobalExceptionHandler.java)

## Protocol definitions

For REST behavior, begin with controller classes in the source map and the existing service documentation; this inventory does not infer endpoints from class names.

Tracked Protocol Buffer definitions:

- [slot_safety.proto](../../slot-hold-engine/src/main/proto/slot_safety.proto)

Consult the Maven descriptor for generation plugins before regenerating clients.

## Test inventory

Test sources and fixtures listed here are navigation aids, not a report of passing tests. Review the test setup for Docker, Testcontainers, external services, and profile requirements before execution.

- [CounterHoldsTest.java](../../slot-hold-engine/src/test/java/com/fcourt/hold/CounterHoldsTest.java)
- [HoldIntegrationTest.java](../../slot-hold-engine/src/test/java/com/fcourt/hold/HoldIntegrationTest.java)
- [OpenApiDocsTest.java](../../slot-hold-engine/src/test/java/com/fcourt/hold/OpenApiDocsTest.java)
- [GlobalExceptionHandlerTest.java](../../slot-hold-engine/src/test/java/com/fcourt/hold/exception/GlobalExceptionHandlerTest.java)

From the repository root, the standard Maven verification entry point is `mvn -B -f slot-hold-engine/pom.xml verify`. Check the descriptor and environment requirements first.

## Database scripts and migrations

Review script contents and application configuration to distinguish initialization scripts from migrations loaded at runtime. Do not infer execution order across these two locations, or execute scripts against a shared database merely because they are listed here.

- [V1__init_schema.sql](../../slot-hold-engine/database/V1__init_schema.sql)
