# notification-service: repository reference

[Documentation index](../README.md) | [Service reference index](README.md)

This reference maps tracked files to their purpose for repository navigation. It describes file locations, not evidence that the service has been built, tested, or deployed.

## Service directory

The service lives in [`notification-service/`](../../notification-service/). Its build descriptor is [`pom.xml`](../../notification-service/pom.xml). Paths below are relative to the repository root. Generated output and untracked local configuration are excluded.

## Build and container entry points

Inspect the Maven descriptor for dependencies and plugins, and the Dockerfile or Compose descriptor when present for container behavior. These links identify definitions; they do not execute them.

- [Dockerfile](../../notification-service/Dockerfile)
- [mvnw](../../notification-service/mvnw)
- [mvnw.cmd](../../notification-service/mvnw.cmd)
- [pom.xml](../../notification-service/pom.xml)

## Application resources

Tracked application resources are listed below. Inspect property placeholders and profile selection before choosing local values; do not copy credentials into documentation.

- [application.yml](../../notification-service/src/main/resources/application.yml)
- [booking-cancelled.html](../../notification-service/src/main/resources/mail/booking-cancelled.html)
- [booking-cancelled.txt](../../notification-service/src/main/resources/mail/booking-cancelled.txt)
- [booking-confirmed.html](../../notification-service/src/main/resources/mail/booking-confirmed.html)
- [booking-confirmed.txt](../../notification-service/src/main/resources/mail/booking-confirmed.txt)
- [booking-reminder.html](../../notification-service/src/main/resources/mail/booking-reminder.html)
- [booking-reminder.txt](../../notification-service/src/main/resources/mail/booking-reminder.txt)
- [owner-approved.html](../../notification-service/src/main/resources/mail/owner-approved.html)
- [owner-approved.txt](../../notification-service/src/main/resources/mail/owner-approved.txt)
- [password-reset.html](../../notification-service/src/main/resources/mail/password-reset.html)
- [password-reset.txt](../../notification-service/src/main/resources/mail/password-reset.txt)
