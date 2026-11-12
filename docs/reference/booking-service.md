# booking-service: repository reference

[Documentation index](../README.md) | [Service reference index](README.md)

This reference maps tracked files to their purpose for repository navigation. It describes file locations, not evidence that the service has been built, tested, or deployed.

## Service directory

The service lives in [`booking-service/`](../../booking-service/). Its build descriptor is [`pom.xml`](../../booking-service/pom.xml). Paths below are relative to the repository root. Generated output and untracked local configuration are excluded.

## Build and container entry points

Inspect the Maven descriptor for dependencies and plugins, and the Dockerfile or Compose descriptor when present for container behavior. These links identify definitions; they do not execute them.

- [Dockerfile](../../booking-service/Dockerfile)
- [mvnw](../../booking-service/mvnw)
- [mvnw.cmd](../../booking-service/mvnw.cmd)
- [pom.xml](../../booking-service/pom.xml)

## Application resources

Tracked application resources are listed below. Inspect property placeholders and profile selection before choosing local values; do not copy credentials into documentation.

- [application-realtime.yml](../../booking-service/src/main/resources/application-realtime.yml)
- [application.yml](../../booking-service/src/main/resources/application.yml)

## Java source map

Java files are grouped by their tracked source directory. Package location alone does not establish an API contract or runtime dependency.

### `com/fcourt/booking`

- [AdminBookingCancellation.java](../../booking-service/src/main/java/com/fcourt/booking/AdminBookingCancellation.java)
- [AdminBookingController.java](../../booking-service/src/main/java/com/fcourt/booking/AdminBookingController.java)
- [AdminBookingFilter.java](../../booking-service/src/main/java/com/fcourt/booking/AdminBookingFilter.java)
- [AdminBookingQueries.java](../../booking-service/src/main/java/com/fcourt/booking/AdminBookingQueries.java)
- [AdminBookingView.java](../../booking-service/src/main/java/com/fcourt/booking/AdminBookingView.java)
- [AdminBranchAuthority.java](../../booking-service/src/main/java/com/fcourt/booking/AdminBranchAuthority.java)
- [BookingApplication.java](../../booking-service/src/main/java/com/fcourt/booking/BookingApplication.java)
- [BookingCancellation.java](../../booking-service/src/main/java/com/fcourt/booking/BookingCancellation.java)
- [BookingCancellationController.java](../../booking-service/src/main/java/com/fcourt/booking/BookingCancellationController.java)
- [BookingController.java](../../booking-service/src/main/java/com/fcourt/booking/BookingController.java)
- [BookingKafka.java](../../booking-service/src/main/java/com/fcourt/booking/BookingKafka.java)
- [BookingOutboxRelay.java](../../booking-service/src/main/java/com/fcourt/booking/BookingOutboxRelay.java)
- [BookingPaymentEvents.java](../../booking-service/src/main/java/com/fcourt/booking/BookingPaymentEvents.java)
- [BookingPosCharges.java](../../booking-service/src/main/java/com/fcourt/booking/BookingPosCharges.java)
- [BookingQueries.java](../../booking-service/src/main/java/com/fcourt/booking/BookingQueries.java)
- [BookingSecurity.java](../../booking-service/src/main/java/com/fcourt/booking/BookingSecurity.java)
- [BookingView.java](../../booking-service/src/main/java/com/fcourt/booking/BookingView.java)
- [BookingVoucherBackfill.java](../../booking-service/src/main/java/com/fcourt/booking/BookingVoucherBackfill.java)
- [BookingVoucherBridge.java](../../booking-service/src/main/java/com/fcourt/booking/BookingVoucherBridge.java)
- [BookingVoucherController.java](../../booking-service/src/main/java/com/fcourt/booking/BookingVoucherController.java)
- [BookingVoucherEvents.java](../../booking-service/src/main/java/com/fcourt/booking/BookingVoucherEvents.java)
- [CounterDependencies.java](../../booking-service/src/main/java/com/fcourt/booking/CounterDependencies.java)
- [CounterSafetyServer.java](../../booking-service/src/main/java/com/fcourt/booking/CounterSafetyServer.java)
- [FullCourtCatalog.java](../../booking-service/src/main/java/com/fcourt/booking/FullCourtCatalog.java)
- [FullCourtFacility.java](../../booking-service/src/main/java/com/fcourt/booking/FullCourtFacility.java)
- [FullCourtManagement.java](../../booking-service/src/main/java/com/fcourt/booking/FullCourtManagement.java)
- [FullCourtQuote.java](../../booking-service/src/main/java/com/fcourt/booking/FullCourtQuote.java)
- [FullCourtReconciler.java](../../booking-service/src/main/java/com/fcourt/booking/FullCourtReconciler.java)
- [FullCourtReservation.java](../../booking-service/src/main/java/com/fcourt/booking/FullCourtReservation.java)
- [GuestBookingCancellation.java](../../booking-service/src/main/java/com/fcourt/booking/GuestBookingCancellation.java)
- [OnlineBooking.java](../../booking-service/src/main/java/com/fcourt/booking/OnlineBooking.java)
- [OnlineBookingDependencies.java](../../booking-service/src/main/java/com/fcourt/booking/OnlineBookingDependencies.java)
- [OnlineBookingExpiry.java](../../booking-service/src/main/java/com/fcourt/booking/OnlineBookingExpiry.java)
- [OnlineHoldReleaseWorker.java](../../booking-service/src/main/java/com/fcourt/booking/OnlineHoldReleaseWorker.java)
- [ReceiptUrls.java](../../booking-service/src/main/java/com/fcourt/booking/ReceiptUrls.java)
- [SlotChanges.java](../../booking-service/src/main/java/com/fcourt/booking/SlotChanges.java)
- [SlotOccupancy.java](../../booking-service/src/main/java/com/fcourt/booking/SlotOccupancy.java)
- [SlotWebSocket.java](../../booking-service/src/main/java/com/fcourt/booking/SlotWebSocket.java)
- [StaffAdmissionController.java](../../booking-service/src/main/java/com/fcourt/booking/StaffAdmissionController.java)
- [StaffAdmissions.java](../../booking-service/src/main/java/com/fcourt/booking/StaffAdmissions.java)
- [StaffAuthority.java](../../booking-service/src/main/java/com/fcourt/booking/StaffAuthority.java)
- [StaffCounter.java](../../booking-service/src/main/java/com/fcourt/booking/StaffCounter.java)
- [StaffCounterController.java](../../booking-service/src/main/java/com/fcourt/booking/StaffCounterController.java)

### `com/fcourt/booking/config`

- [OpenApiConfig.java](../../booking-service/src/main/java/com/fcourt/booking/config/OpenApiConfig.java)

### `com/fcourt/booking/controller`

- [GuestBookingCancelController.java](../../booking-service/src/main/java/com/fcourt/booking/controller/GuestBookingCancelController.java)
- [GuestBookingLookupController.java](../../booking-service/src/main/java/com/fcourt/booking/controller/GuestBookingLookupController.java)

### `com/fcourt/booking/dto/request`

- [AdminCancelRequest.java](../../booking-service/src/main/java/com/fcourt/booking/dto/request/AdminCancelRequest.java)
- [GuestCancelRequest.java](../../booking-service/src/main/java/com/fcourt/booking/dto/request/GuestCancelRequest.java)
- [GuestLookupRequest.java](../../booking-service/src/main/java/com/fcourt/booking/dto/request/GuestLookupRequest.java)

### `com/fcourt/booking/dto/response`

- [GuestBookingResponse.java](../../booking-service/src/main/java/com/fcourt/booking/dto/response/GuestBookingResponse.java)

### `com/fcourt/booking/exception`

- [CheckInRejectedException.java](../../booking-service/src/main/java/com/fcourt/booking/exception/CheckInRejectedException.java)
- [GlobalExceptionHandler.java](../../booking-service/src/main/java/com/fcourt/booking/exception/GlobalExceptionHandler.java)

### `com/fcourt/booking/service`

- [GuestBookingLookupService.java](../../booking-service/src/main/java/com/fcourt/booking/service/GuestBookingLookupService.java)

## Protocol definitions

For REST behavior, begin with controller classes in the source map and the existing service documentation; this inventory does not infer endpoints from class names.

Tracked Protocol Buffer definitions:

- [slot_safety.proto](../../booking-service/src/main/proto/slot_safety.proto)

Consult the Maven descriptor for generation plugins before regenerating clients.

## Test inventory

Test sources and fixtures listed here are navigation aids, not a report of passing tests. Review the test setup for Docker, Testcontainers, external services, and profile requirements before execution.

- [AdminBookingApiTest.java](../../booking-service/src/test/java/com/fcourt/booking/AdminBookingApiTest.java)
- [AdminBookingCancelApiTest.java](../../booking-service/src/test/java/com/fcourt/booking/AdminBookingCancelApiTest.java)
- [AdminBranchAuthorityTest.java](../../booking-service/src/test/java/com/fcourt/booking/AdminBranchAuthorityTest.java)
- [BookingApiTest.java](../../booking-service/src/test/java/com/fcourt/booking/BookingApiTest.java)
- [BookingCancellationTest.java](../../booking-service/src/test/java/com/fcourt/booking/BookingCancellationTest.java)
- [CancellationRefundContractTest.java](../../booking-service/src/test/java/com/fcourt/booking/CancellationRefundContractTest.java)
- [CounterSafetyServerTest.java](../../booking-service/src/test/java/com/fcourt/booking/CounterSafetyServerTest.java)
- [FullCourtCatalogTest.java](../../booking-service/src/test/java/com/fcourt/booking/FullCourtCatalogTest.java)
- [FullCourtFacilityTest.java](../../booking-service/src/test/java/com/fcourt/booking/FullCourtFacilityTest.java)
- [FullCourtQuoteTest.java](../../booking-service/src/test/java/com/fcourt/booking/FullCourtQuoteTest.java)
- [FullCourtReservationTest.java](../../booking-service/src/test/java/com/fcourt/booking/FullCourtReservationTest.java)
- [GuestBookingCancelApiTest.java](../../booking-service/src/test/java/com/fcourt/booking/GuestBookingCancelApiTest.java)
- [GuestBookingLookupApiTest.java](../../booking-service/src/test/java/com/fcourt/booking/GuestBookingLookupApiTest.java)
- [LocalFixtureApplication.java](../../booking-service/src/test/java/com/fcourt/booking/LocalFixtureApplication.java)
- [OnlineBookingDependenciesTest.java](../../booking-service/src/test/java/com/fcourt/booking/OnlineBookingDependenciesTest.java)
- [OnlineBookingTest.java](../../booking-service/src/test/java/com/fcourt/booking/OnlineBookingTest.java)
- [OpenApiDocsTest.java](../../booking-service/src/test/java/com/fcourt/booking/OpenApiDocsTest.java)
- [ReceiptUrlsTest.java](../../booking-service/src/test/java/com/fcourt/booking/ReceiptUrlsTest.java)
- [SlotFanoutLatencyTest.java](../../booking-service/src/test/java/com/fcourt/booking/SlotFanoutLatencyTest.java)
- [SlotLatencyHarness.java](../../booking-service/src/test/java/com/fcourt/booking/SlotLatencyHarness.java)
- [SlotOccupancyTest.java](../../booking-service/src/test/java/com/fcourt/booking/SlotOccupancyTest.java)
- [SlotRealtimeTest.java](../../booking-service/src/test/java/com/fcourt/booking/SlotRealtimeTest.java)
- [SlotRedisLatencyIntegrationTest.java](../../booking-service/src/test/java/com/fcourt/booking/SlotRedisLatencyIntegrationTest.java)
- [SlotWebSocketIntegrationTest.java](../../booking-service/src/test/java/com/fcourt/booking/SlotWebSocketIntegrationTest.java)
- [StaffAdmissionsTest.java](../../booking-service/src/test/java/com/fcourt/booking/StaffAdmissionsTest.java)
- [StaffAuthorityTest.java](../../booking-service/src/test/java/com/fcourt/booking/StaffAuthorityTest.java)
- [StaffCounterTest.java](../../booking-service/src/test/java/com/fcourt/booking/StaffCounterTest.java)
- [GlobalExceptionHandlerTest.java](../../booking-service/src/test/java/com/fcourt/booking/exception/GlobalExceptionHandlerTest.java)

From the repository root, the standard Maven verification entry point is `mvn -B -f booking-service/pom.xml verify`. Check the descriptor and environment requirements first.

## Database scripts and migrations

Review script contents and application configuration to distinguish initialization scripts from migrations loaded at runtime. Do not infer execution order across these two locations, or execute scripts against a shared database merely because they are listed here.

- [V1__init_schema.sql](../../booking-service/database/V1__init_schema.sql)
- [V2__seed_sample_data.sql](../../booking-service/database/V2__seed_sample_data.sql)
- [V10__booking_cancelled_by.sql](../../booking-service/src/main/resources/db/migration/V10__booking_cancelled_by.sql)
- [V1__booking_schema.sql](../../booking-service/src/main/resources/db/migration/V1__booking_schema.sql)
- [V2__booking_read_model.sql](../../booking-service/src/main/resources/db/migration/V2__booking_read_model.sql)
- [V3__cancellation_outbox.sql](../../booking-service/src/main/resources/db/migration/V3__cancellation_outbox.sql)
- [V4__staff_admission_commands.sql](../../booking-service/src/main/resources/db/migration/V4__staff_admission_commands.sql)
- [V5__counter_booking_authority.sql](../../booking-service/src/main/resources/db/migration/V5__counter_booking_authority.sql)
- [V6__booking_pos_charges.sql](../../booking-service/src/main/resources/db/migration/V6__booking_pos_charges.sql)
- [V7__online_booking_commands.sql](../../booking-service/src/main/resources/db/migration/V7__online_booking_commands.sql)
- [V8__booking_vouchers.sql](../../booking-service/src/main/resources/db/migration/V8__booking_vouchers.sql)
- [V9__full_court_packages.sql](../../booking-service/src/main/resources/db/migration/V9__full_court_packages.sql)

## Related documentation

Read the existing service documentation for behavior, setup instructions, and recorded validation details. Historical validation notes describe the run they record and are not fresh verification results.

- [FULL_COURT.md](../../booking-service/FULL_COURT.md)
- [ONLINE_BOOKING.md](../../booking-service/ONLINE_BOOKING.md)
- [README.md](../../booking-service/README.md)

For shared runtime setup, see [Docker Compose operations](../operations/docker-compose.md). For request routing, see [gateway operations](../operations/gateway.md).
