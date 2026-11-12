# Backend test source index

Find Java test sources by module and class name, followed by the Python tests
for the demo seed, production smoke runner, and system-flow tooling. Java entries
use the tracked `*Test.java` and `*Tests.java` naming patterns; supporting fixtures
and classes with other names are outside this index.

These links identify source files, not test execution results or coverage claims.
Read the owning module's README and build configuration for dependencies and
commands. Some tests require Docker, PostgreSQL, Kafka, Redis, or other services.
Opening a source file does not execute it.

Paths are relative to this document. Entries reflect the source tree when this
index was prepared and should be updated when a linked file moves or is removed.

| Module or tool | Test source |
| --- | --- |
| auth-service | [OpenApiDocsTest.java](../auth-service/src/test/java/com/fcourt/auth/OpenApiDocsTest.java) |
| auth-service | [SuperAdminApiIntegrationTest.java](../auth-service/src/test/java/com/fcourt/auth/SuperAdminApiIntegrationTest.java) |
| auth-service | [AuthApiIntegrationTest.java](../auth-service/src/test/java/com/fcourt/auth/account/AuthApiIntegrationTest.java) |
| auth-service | [AuthControllerTest.java](../auth-service/src/test/java/com/fcourt/auth/account/AuthControllerTest.java) |
| auth-service | [AuthServiceTest.java](../auth-service/src/test/java/com/fcourt/auth/account/AuthServiceTest.java) |
| auth-service | [AvatarControllerTest.java](../auth-service/src/test/java/com/fcourt/auth/account/AvatarControllerTest.java) |
| auth-service | [AvatarServiceTest.java](../auth-service/src/test/java/com/fcourt/auth/account/AvatarServiceTest.java) |
| auth-service | [FailedAttemptThrottleTest.java](../auth-service/src/test/java/com/fcourt/auth/account/FailedAttemptThrottleTest.java) |
| auth-service | [GoogleLoginServiceTest.java](../auth-service/src/test/java/com/fcourt/auth/account/GoogleLoginServiceTest.java) |
| auth-service | [PasswordChangeTokenValidatorTest.java](../auth-service/src/test/java/com/fcourt/auth/account/PasswordChangeTokenValidatorTest.java) |
| auth-service | [PasswordControllerTest.java](../auth-service/src/test/java/com/fcourt/auth/account/PasswordControllerTest.java) |
| auth-service | [PasswordServiceTest.java](../auth-service/src/test/java/com/fcourt/auth/account/PasswordServiceTest.java) |
| auth-service | [S3DocumentStorageContainerTest.java](../auth-service/src/test/java/com/fcourt/auth/account/S3DocumentStorageContainerTest.java) |
| auth-service | [S3DocumentStorageTest.java](../auth-service/src/test/java/com/fcourt/auth/account/S3DocumentStorageTest.java) |
| auth-service | [SessionServiceTest.java](../auth-service/src/test/java/com/fcourt/auth/account/SessionServiceTest.java) |
| auth-service | [SecurityConfigurationTest.java](../auth-service/src/test/java/com/fcourt/auth/config/SecurityConfigurationTest.java) |
| auth-service | [StaffAccountCommandsTest.java](../auth-service/src/test/java/com/fcourt/auth/staff/StaffAccountCommandsTest.java) |
| auth-service | [StaffAccountsTest.java](../auth-service/src/test/java/com/fcourt/auth/staff/StaffAccountsTest.java) |
| auth-service | [StaffReactivationTest.java](../auth-service/src/test/java/com/fcourt/auth/staff/StaffReactivationTest.java) |
| auth-service | [PlatformStatsRepositoryIntegrationTest.java](../auth-service/src/test/java/com/fcourt/auth/superadmin/PlatformStatsRepositoryIntegrationTest.java) |
| auth-service | [PlatformStatsServiceTest.java](../auth-service/src/test/java/com/fcourt/auth/superadmin/PlatformStatsServiceTest.java) |
| auth-service | [SuperAdminServiceTest.java](../auth-service/src/test/java/com/fcourt/auth/superadmin/SuperAdminServiceTest.java) |
