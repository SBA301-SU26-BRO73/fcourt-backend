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
