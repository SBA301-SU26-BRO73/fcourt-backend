# pos-retail-service: repository reference

[Documentation index](../README.md) | [Service reference index](README.md)

This reference maps tracked files to their purpose for repository navigation. It describes file locations, not evidence that the service has been built, tested, or deployed.

## Service directory

The service lives in [`pos-retail-service/`](../../pos-retail-service/). Its build descriptor is [`pom.xml`](../../pos-retail-service/pom.xml). Paths below are relative to the repository root. Generated output and untracked local configuration are excluded.

## Build and container entry points

Inspect the Maven descriptor for dependencies and plugins, and the Dockerfile or Compose descriptor when present for container behavior. These links identify definitions; they do not execute them.

- [Dockerfile](../../pos-retail-service/Dockerfile)
- [compose.yml](../../pos-retail-service/compose.yml)
- [pom.xml](../../pos-retail-service/pom.xml)

## Application resources

Tracked application resources are listed below. Inspect property placeholders and profile selection before choosing local values; do not copy credentials into documentation.

- [application.yml](../../pos-retail-service/src/main/resources/application.yml)

## Java source map

Java files are grouped by their tracked source directory. Package location alone does not establish an API contract or runtime dependency.

### `com/fcourt/pos`

- [PosApplication.java](../../pos-retail-service/src/main/java/com/fcourt/pos/PosApplication.java)

### `com/fcourt/pos/api`

- [PosController.java](../../pos-retail-service/src/main/java/com/fcourt/pos/api/PosController.java)
- [PosErrors.java](../../pos-retail-service/src/main/java/com/fcourt/pos/api/PosErrors.java)
- [PosRequests.java](../../pos-retail-service/src/main/java/com/fcourt/pos/api/PosRequests.java)
- [PosViews.java](../../pos-retail-service/src/main/java/com/fcourt/pos/api/PosViews.java)

### `com/fcourt/pos/config`

- [OpenApiConfig.java](../../pos-retail-service/src/main/java/com/fcourt/pos/config/OpenApiConfig.java)
- [PosConfiguration.java](../../pos-retail-service/src/main/java/com/fcourt/pos/config/PosConfiguration.java)
- [PosSecurity.java](../../pos-retail-service/src/main/java/com/fcourt/pos/config/PosSecurity.java)

### `com/fcourt/pos/dto/response`

- [ProductPageResponse.java](../../pos-retail-service/src/main/java/com/fcourt/pos/dto/response/ProductPageResponse.java)

### `com/fcourt/pos/integration`

- [PosUpstreams.java](../../pos-retail-service/src/main/java/com/fcourt/pos/integration/PosUpstreams.java)

### `com/fcourt/pos/persistence`

- [PosStore.java](../../pos-retail-service/src/main/java/com/fcourt/pos/persistence/PosStore.java)

### `com/fcourt/pos/service`

- [PosCommands.java](../../pos-retail-service/src/main/java/com/fcourt/pos/service/PosCommands.java)
- [PosDispatchWorker.java](../../pos-retail-service/src/main/java/com/fcourt/pos/service/PosDispatchWorker.java)
- [ProductService.java](../../pos-retail-service/src/main/java/com/fcourt/pos/service/ProductService.java)
- [SalesService.java](../../pos-retail-service/src/main/java/com/fcourt/pos/service/SalesService.java)
- [SettlementService.java](../../pos-retail-service/src/main/java/com/fcourt/pos/service/SettlementService.java)

## Protocol definitions

For REST behavior, begin with controller classes in the source map and the existing service documentation; this inventory does not infer endpoints from class names.

No `.proto` files are tracked in this service directory. This does not imply that the service makes no calls to other services.
