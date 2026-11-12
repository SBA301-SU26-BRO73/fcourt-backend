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
| auth-service | [AccessTokenIssuerTest.java](../auth-service/src/test/java/com/fcourt/auth/token/AccessTokenIssuerTest.java) |
| auth-service | [JwksControllerTest.java](../auth-service/src/test/java/com/fcourt/auth/token/JwksControllerTest.java) |
| auth-service | [SigningKeyConfigurationTest.java](../auth-service/src/test/java/com/fcourt/auth/token/SigningKeyConfigurationTest.java) |
| booking-service | [AdminBookingApiTest.java](../booking-service/src/test/java/com/fcourt/booking/AdminBookingApiTest.java) |
| booking-service | [AdminBookingCancelApiTest.java](../booking-service/src/test/java/com/fcourt/booking/AdminBookingCancelApiTest.java) |
| booking-service | [AdminBranchAuthorityTest.java](../booking-service/src/test/java/com/fcourt/booking/AdminBranchAuthorityTest.java) |
| booking-service | [BookingApiTest.java](../booking-service/src/test/java/com/fcourt/booking/BookingApiTest.java) |
| booking-service | [BookingCancellationTest.java](../booking-service/src/test/java/com/fcourt/booking/BookingCancellationTest.java) |
| booking-service | [CancellationRefundContractTest.java](../booking-service/src/test/java/com/fcourt/booking/CancellationRefundContractTest.java) |
| booking-service | [CounterSafetyServerTest.java](../booking-service/src/test/java/com/fcourt/booking/CounterSafetyServerTest.java) |
| booking-service | [FullCourtCatalogTest.java](../booking-service/src/test/java/com/fcourt/booking/FullCourtCatalogTest.java) |
| booking-service | [FullCourtFacilityTest.java](../booking-service/src/test/java/com/fcourt/booking/FullCourtFacilityTest.java) |
| booking-service | [FullCourtQuoteTest.java](../booking-service/src/test/java/com/fcourt/booking/FullCourtQuoteTest.java) |
| booking-service | [FullCourtReservationTest.java](../booking-service/src/test/java/com/fcourt/booking/FullCourtReservationTest.java) |
| booking-service | [GuestBookingCancelApiTest.java](../booking-service/src/test/java/com/fcourt/booking/GuestBookingCancelApiTest.java) |
| booking-service | [GuestBookingLookupApiTest.java](../booking-service/src/test/java/com/fcourt/booking/GuestBookingLookupApiTest.java) |
| booking-service | [OnlineBookingDependenciesTest.java](../booking-service/src/test/java/com/fcourt/booking/OnlineBookingDependenciesTest.java) |
| booking-service | [OnlineBookingTest.java](../booking-service/src/test/java/com/fcourt/booking/OnlineBookingTest.java) |
| booking-service | [OpenApiDocsTest.java](../booking-service/src/test/java/com/fcourt/booking/OpenApiDocsTest.java) |
| booking-service | [ReceiptUrlsTest.java](../booking-service/src/test/java/com/fcourt/booking/ReceiptUrlsTest.java) |
| booking-service | [SlotFanoutLatencyTest.java](../booking-service/src/test/java/com/fcourt/booking/SlotFanoutLatencyTest.java) |
| booking-service | [SlotOccupancyTest.java](../booking-service/src/test/java/com/fcourt/booking/SlotOccupancyTest.java) |
| booking-service | [SlotRealtimeTest.java](../booking-service/src/test/java/com/fcourt/booking/SlotRealtimeTest.java) |
| booking-service | [SlotRedisLatencyIntegrationTest.java](../booking-service/src/test/java/com/fcourt/booking/SlotRedisLatencyIntegrationTest.java) |
| booking-service | [SlotWebSocketIntegrationTest.java](../booking-service/src/test/java/com/fcourt/booking/SlotWebSocketIntegrationTest.java) |
| booking-service | [StaffAdmissionsTest.java](../booking-service/src/test/java/com/fcourt/booking/StaffAdmissionsTest.java) |
| booking-service | [StaffAuthorityTest.java](../booking-service/src/test/java/com/fcourt/booking/StaffAuthorityTest.java) |
| booking-service | [StaffCounterTest.java](../booking-service/src/test/java/com/fcourt/booking/StaffCounterTest.java) |
| booking-service | [GlobalExceptionHandlerTest.java](../booking-service/src/test/java/com/fcourt/booking/exception/GlobalExceptionHandlerTest.java) |
| discovery-service | [DiscoveryIntegrationTest.java](../discovery-service/src/test/java/com/fcourt/discovery/DiscoveryIntegrationTest.java) |
| discovery-service | [ReviewIntegrationTest.java](../discovery-service/src/test/java/com/fcourt/discovery/ReviewIntegrationTest.java) |
| discovery-service | [BranchEventListenerTest.java](../discovery-service/src/test/java/com/fcourt/discovery/component/BranchEventListenerTest.java) |
| discovery-service | [ReviewImageCleanupTest.java](../discovery-service/src/test/java/com/fcourt/discovery/component/ReviewImageCleanupTest.java) |
| discovery-service | [ReviewImagePolicyTest.java](../discovery-service/src/test/java/com/fcourt/discovery/component/ReviewImagePolicyTest.java) |
| discovery-service | [ReviewImageValidatorTest.java](../discovery-service/src/test/java/com/fcourt/discovery/component/ReviewImageValidatorTest.java) |
| discovery-service | [ReviewUploadLimiterTest.java](../discovery-service/src/test/java/com/fcourt/discovery/component/ReviewUploadLimiterTest.java) |
| discovery-service | [ReviewerNamesTest.java](../discovery-service/src/test/java/com/fcourt/discovery/component/ReviewerNamesTest.java) |
| discovery-service | [ReviewerProfileClientTest.java](../discovery-service/src/test/java/com/fcourt/discovery/component/ReviewerProfileClientTest.java) |
| discovery-service | [S3ImageStorageTest.java](../discovery-service/src/test/java/com/fcourt/discovery/component/S3ImageStorageTest.java) |
| discovery-service | [SlotSourceClientTest.java](../discovery-service/src/test/java/com/fcourt/discovery/component/SlotSourceClientTest.java) |
| discovery-service | [InternalSlotConflictsSecurityTest.java](../discovery-service/src/test/java/com/fcourt/discovery/config/InternalSlotConflictsSecurityTest.java) |
| discovery-service | [KafkaConfigurationTest.java](../discovery-service/src/test/java/com/fcourt/discovery/config/KafkaConfigurationTest.java) |
| discovery-service | [OpenApiDocsTest.java](../discovery-service/src/test/java/com/fcourt/discovery/config/OpenApiDocsTest.java) |
| discovery-service | [BranchControllerTest.java](../discovery-service/src/test/java/com/fcourt/discovery/controller/BranchControllerTest.java) |
| discovery-service | [CourtSlotControllerTest.java](../discovery-service/src/test/java/com/fcourt/discovery/controller/CourtSlotControllerTest.java) |
| discovery-service | [SlotHoldRepositoryTest.java](../discovery-service/src/test/java/com/fcourt/discovery/repository/SlotHoldRepositoryTest.java) |
| discovery-service | [BranchAvailabilitySearchTest.java](../discovery-service/src/test/java/com/fcourt/discovery/service/BranchAvailabilitySearchTest.java) |
| discovery-service | [BranchSearchServiceTest.java](../discovery-service/src/test/java/com/fcourt/discovery/service/BranchSearchServiceTest.java) |
| discovery-service | [CourtSlotServiceTest.java](../discovery-service/src/test/java/com/fcourt/discovery/service/CourtSlotServiceTest.java) |
| discovery-service | [ReviewImageServiceTest.java](../discovery-service/src/test/java/com/fcourt/discovery/service/ReviewImageServiceTest.java) |
| facility-service | [BranchImageApiIntegrationTest.java](../facility-service/src/test/java/com/fcourt/facility/BranchImageApiIntegrationTest.java) |
| facility-service | [CourtImageApiIntegrationTest.java](../facility-service/src/test/java/com/fcourt/facility/CourtImageApiIntegrationTest.java) |
| facility-service | [CourtReservationIntegrationTest.java](../facility-service/src/test/java/com/fcourt/facility/CourtReservationIntegrationTest.java) |
| facility-service | [FacilityApiIntegrationTest.java](../facility-service/src/test/java/com/fcourt/facility/FacilityApiIntegrationTest.java) |
| facility-service | [OpenApiDocsTest.java](../facility-service/src/test/java/com/fcourt/facility/OpenApiDocsTest.java) |
| facility-service | [PricingRulesIntegrationTest.java](../facility-service/src/test/java/com/fcourt/facility/PricingRulesIntegrationTest.java) |
| facility-service | [BranchOwnerControllerTest.java](../facility-service/src/test/java/com/fcourt/facility/api/BranchOwnerControllerTest.java) |
| facility-service | [BranchRequestValidationTest.java](../facility-service/src/test/java/com/fcourt/facility/api/BranchRequestValidationTest.java) |
| facility-service | [BulkApplyRequestValidationTest.java](../facility-service/src/test/java/com/fcourt/facility/api/BulkApplyRequestValidationTest.java) |
| facility-service | [LockRequestValidationTest.java](../facility-service/src/test/java/com/fcourt/facility/api/LockRequestValidationTest.java) |
| facility-service | [PricingRequestValidationTest.java](../facility-service/src/test/java/com/fcourt/facility/api/PricingRequestValidationTest.java) |
| facility-service | [ReservationControllerTest.java](../facility-service/src/test/java/com/fcourt/facility/api/ReservationControllerTest.java) |
| facility-service | [TemplateRequestValidationTest.java](../facility-service/src/test/java/com/fcourt/facility/api/TemplateRequestValidationTest.java) |
| facility-service | [RedisLockScheduleEventsTest.java](../facility-service/src/test/java/com/fcourt/facility/events/RedisLockScheduleEventsTest.java) |
| facility-service | [ReservationRealtimeBridgeTest.java](../facility-service/src/test/java/com/fcourt/facility/events/ReservationRealtimeBridgeTest.java) |
| facility-service | [S3ImageStorageContainerTest.java](../facility-service/src/test/java/com/fcourt/facility/integration/S3ImageStorageContainerTest.java) |
