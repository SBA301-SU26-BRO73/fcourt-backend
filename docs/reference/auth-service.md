# auth-service: repository reference

[Documentation index](../README.md) | [Service reference index](README.md)

This reference maps tracked files to their purpose for repository navigation. It describes file locations, not evidence that the service has been built, tested, or deployed.

## Service directory

The service lives in [`auth-service/`](../../auth-service/). Its build descriptor is [`pom.xml`](../../auth-service/pom.xml). Paths below are relative to the repository root. Generated output and untracked local configuration are excluded.

## Build and container entry points

Inspect the Maven descriptor for dependencies and plugins, and the Dockerfile or Compose descriptor when present for container behavior. These links identify definitions; they do not execute them.

- [Dockerfile](../../auth-service/Dockerfile)
- [mvnw](../../auth-service/mvnw)
- [mvnw.cmd](../../auth-service/mvnw.cmd)
- [pom.xml](../../auth-service/pom.xml)

## Application resources

Tracked application resources are listed below. Inspect property placeholders and profile selection before choosing local values; do not copy credentials into documentation.

- [application.yml](../../auth-service/src/main/resources/application.yml)

## Java source map

Java files are grouped by their tracked source directory. Package location alone does not establish an API contract or runtime dependency.

### `com/fcourt/auth`

- [AuthApplication.java](../../auth-service/src/main/java/com/fcourt/auth/AuthApplication.java)

### `com/fcourt/auth/account`

- [AccountDtos.java](../../auth-service/src/main/java/com/fcourt/auth/account/AccountDtos.java)
- [AccountPolicy.java](../../auth-service/src/main/java/com/fcourt/auth/account/AccountPolicy.java)
- [AccountRepository.java](../../auth-service/src/main/java/com/fcourt/auth/account/AccountRepository.java)
- [AuthController.java](../../auth-service/src/main/java/com/fcourt/auth/account/AuthController.java)
- [AuthService.java](../../auth-service/src/main/java/com/fcourt/auth/account/AuthService.java)
- [AvatarController.java](../../auth-service/src/main/java/com/fcourt/auth/account/AvatarController.java)
- [AvatarService.java](../../auth-service/src/main/java/com/fcourt/auth/account/AvatarService.java)
- [DocumentStorage.java](../../auth-service/src/main/java/com/fcourt/auth/account/DocumentStorage.java)
- [DocumentStorageConfiguration.java](../../auth-service/src/main/java/com/fcourt/auth/account/DocumentStorageConfiguration.java)
- [FailedAttemptRepository.java](../../auth-service/src/main/java/com/fcourt/auth/account/FailedAttemptRepository.java)
- [FailedAttemptThrottle.java](../../auth-service/src/main/java/com/fcourt/auth/account/FailedAttemptThrottle.java)
- [GoogleLoginController.java](../../auth-service/src/main/java/com/fcourt/auth/account/GoogleLoginController.java)
- [GoogleLoginService.java](../../auth-service/src/main/java/com/fcourt/auth/account/GoogleLoginService.java)
- [MaxUtf8Bytes.java](../../auth-service/src/main/java/com/fcourt/auth/account/MaxUtf8Bytes.java)
- [OwnerRegisteredEvent.java](../../auth-service/src/main/java/com/fcourt/auth/account/OwnerRegisteredEvent.java)
- [PasswordChangeTokenValidator.java](../../auth-service/src/main/java/com/fcourt/auth/account/PasswordChangeTokenValidator.java)
- [PasswordController.java](../../auth-service/src/main/java/com/fcourt/auth/account/PasswordController.java)
- [PasswordResetRepository.java](../../auth-service/src/main/java/com/fcourt/auth/account/PasswordResetRepository.java)
- [PasswordResetRequestedEvent.java](../../auth-service/src/main/java/com/fcourt/auth/account/PasswordResetRequestedEvent.java)
- [PasswordService.java](../../auth-service/src/main/java/com/fcourt/auth/account/PasswordService.java)
- [RefreshTokenRepository.java](../../auth-service/src/main/java/com/fcourt/auth/account/RefreshTokenRepository.java)
- [S3DocumentStorage.java](../../auth-service/src/main/java/com/fcourt/auth/account/S3DocumentStorage.java)
- [SessionService.java](../../auth-service/src/main/java/com/fcourt/auth/account/SessionService.java)
- [StoredFileType.java](../../auth-service/src/main/java/com/fcourt/auth/account/StoredFileType.java)

### `com/fcourt/auth/common`

- [ApiException.java](../../auth-service/src/main/java/com/fcourt/auth/common/ApiException.java)
- [ApiExceptionHandler.java](../../auth-service/src/main/java/com/fcourt/auth/common/ApiExceptionHandler.java)
- [ProblemResponses.java](../../auth-service/src/main/java/com/fcourt/auth/common/ProblemResponses.java)
- [RetryLaterException.java](../../auth-service/src/main/java/com/fcourt/auth/common/RetryLaterException.java)

### `com/fcourt/auth/config`

- [ClockConfiguration.java](../../auth-service/src/main/java/com/fcourt/auth/config/ClockConfiguration.java)
- [OpenApiConfig.java](../../auth-service/src/main/java/com/fcourt/auth/config/OpenApiConfig.java)
- [PasswordConfiguration.java](../../auth-service/src/main/java/com/fcourt/auth/config/PasswordConfiguration.java)
- [SecurityConfiguration.java](../../auth-service/src/main/java/com/fcourt/auth/config/SecurityConfiguration.java)

### `com/fcourt/auth/outbox`

- [KafkaTopicConfiguration.java](../../auth-service/src/main/java/com/fcourt/auth/outbox/KafkaTopicConfiguration.java)
- [OutboxRelay.java](../../auth-service/src/main/java/com/fcourt/auth/outbox/OutboxRelay.java)
- [OutboxWriter.java](../../auth-service/src/main/java/com/fcourt/auth/outbox/OutboxWriter.java)

### `com/fcourt/auth/staff`

- [ManagedStaffBoundary.java](../../auth-service/src/main/java/com/fcourt/auth/staff/ManagedStaffBoundary.java)
- [StaffAccountController.java](../../auth-service/src/main/java/com/fcourt/auth/staff/StaffAccountController.java)
- [StaffAccounts.java](../../auth-service/src/main/java/com/fcourt/auth/staff/StaffAccounts.java)
- [StaffReactivation.java](../../auth-service/src/main/java/com/fcourt/auth/staff/StaffReactivation.java)

### `com/fcourt/auth/superadmin`

- [OwnerApprovedEvent.java](../../auth-service/src/main/java/com/fcourt/auth/superadmin/OwnerApprovedEvent.java)
- [PlatformStatsController.java](../../auth-service/src/main/java/com/fcourt/auth/superadmin/PlatformStatsController.java)
- [PlatformStatsRepository.java](../../auth-service/src/main/java/com/fcourt/auth/superadmin/PlatformStatsRepository.java)
- [PlatformStatsService.java](../../auth-service/src/main/java/com/fcourt/auth/superadmin/PlatformStatsService.java)
- [SuperAdminController.java](../../auth-service/src/main/java/com/fcourt/auth/superadmin/SuperAdminController.java)
- [SuperAdminDtos.java](../../auth-service/src/main/java/com/fcourt/auth/superadmin/SuperAdminDtos.java)
- [SuperAdminRepository.java](../../auth-service/src/main/java/com/fcourt/auth/superadmin/SuperAdminRepository.java)
- [SuperAdminService.java](../../auth-service/src/main/java/com/fcourt/auth/superadmin/SuperAdminService.java)

### `com/fcourt/auth/token`

- [AccessTokenIssuer.java](../../auth-service/src/main/java/com/fcourt/auth/token/AccessTokenIssuer.java)
- [JwksController.java](../../auth-service/src/main/java/com/fcourt/auth/token/JwksController.java)
- [JwtProperties.java](../../auth-service/src/main/java/com/fcourt/auth/token/JwtProperties.java)
- [SigningKeyConfiguration.java](../../auth-service/src/main/java/com/fcourt/auth/token/SigningKeyConfiguration.java)

### `com/fcourt/auth/user`

- [UserRole.java](../../auth-service/src/main/java/com/fcourt/auth/user/UserRole.java)
- [UserStatus.java](../../auth-service/src/main/java/com/fcourt/auth/user/UserStatus.java)
