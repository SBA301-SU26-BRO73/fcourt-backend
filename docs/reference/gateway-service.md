# gateway-service: repository reference

[Documentation index](../README.md) | [Service reference index](README.md)

This reference maps tracked files to their purpose for repository navigation. It describes file locations, not evidence that the service has been built, tested, or deployed.

## Service directory

The service lives in [`gateway-service/`](../../gateway-service/). Its build descriptor is [`pom.xml`](../../gateway-service/pom.xml). Paths below are relative to the repository root. Generated output and untracked local configuration are excluded.

## Build and container entry points

Inspect the Maven descriptor for dependencies and plugins, and the Dockerfile or Compose descriptor when present for container behavior. These links identify definitions; they do not execute them.

- [Dockerfile](../../gateway-service/Dockerfile)
- [mvnw](../../gateway-service/mvnw)
- [mvnw.cmd](../../gateway-service/mvnw.cmd)
- [pom.xml](../../gateway-service/pom.xml)

## Application resources

Tracked application resources are listed below. Inspect property placeholders and profile selection before choosing local values; do not copy credentials into documentation.

- [application.yml](../../gateway-service/src/main/resources/application.yml)

## Java source map

Java files are grouped by their tracked source directory. Package location alone does not establish an API contract or runtime dependency.

### `com/fcourt/gateway`

- [GatewayApplication.java](../../gateway-service/src/main/java/com/fcourt/gateway/GatewayApplication.java)

### `com/fcourt/gateway/config`

- [GatewayProperties.java](../../gateway-service/src/main/java/com/fcourt/gateway/config/GatewayProperties.java)
- [SwaggerAggregationConfiguration.java](../../gateway-service/src/main/java/com/fcourt/gateway/config/SwaggerAggregationConfiguration.java)

### `com/fcourt/gateway/ratelimit`

- [ClientKeyResolver.java](../../gateway-service/src/main/java/com/fcourt/gateway/ratelimit/ClientKeyResolver.java)
- [PolicyRateLimiter.java](../../gateway-service/src/main/java/com/fcourt/gateway/ratelimit/PolicyRateLimiter.java)
- [RateLimitBackend.java](../../gateway-service/src/main/java/com/fcourt/gateway/ratelimit/RateLimitBackend.java)
- [RateLimitConfiguration.java](../../gateway-service/src/main/java/com/fcourt/gateway/ratelimit/RateLimitConfiguration.java)
- [RateLimitProblemFilter.java](../../gateway-service/src/main/java/com/fcourt/gateway/ratelimit/RateLimitProblemFilter.java)
- [RateLimitProperties.java](../../gateway-service/src/main/java/com/fcourt/gateway/ratelimit/RateLimitProperties.java)

### `com/fcourt/gateway/security`

- [BlockedPathsFilter.java](../../gateway-service/src/main/java/com/fcourt/gateway/security/BlockedPathsFilter.java)
- [Problems.java](../../gateway-service/src/main/java/com/fcourt/gateway/security/Problems.java)
- [SecurityConfiguration.java](../../gateway-service/src/main/java/com/fcourt/gateway/security/SecurityConfiguration.java)

## Protocol definitions

For REST behavior, begin with controller classes in the source map and the existing service documentation; this inventory does not infer endpoints from class names.

No `.proto` files are tracked in this service directory. This does not imply that the service makes no calls to other services.

## Test inventory

Test sources and fixtures listed here are navigation aids, not a report of passing tests. Review the test setup for Docker, Testcontainers, external services, and profile requirements before execution.

- [GatewaySecurityTests.java](../../gateway-service/src/test/java/com/fcourt/gateway/GatewaySecurityTests.java)
- [RateLimitGatewayTests.java](../../gateway-service/src/test/java/com/fcourt/gateway/RateLimitGatewayTests.java)
- [RateLimitRedisDownTests.java](../../gateway-service/src/test/java/com/fcourt/gateway/RateLimitRedisDownTests.java)
- [RouteTableTests.java](../../gateway-service/src/test/java/com/fcourt/gateway/RouteTableTests.java)
- [StaticDiscoveryTests.java](../../gateway-service/src/test/java/com/fcourt/gateway/StaticDiscoveryTests.java)
- [RateLimitUnitTests.java](../../gateway-service/src/test/java/com/fcourt/gateway/ratelimit/RateLimitUnitTests.java)
- [application-test.yml](../../gateway-service/src/test/resources/application-test.yml)

From the repository root, the standard Maven verification entry point is `mvn -B -f gateway-service/pom.xml verify`. Check the descriptor and environment requirements first.
