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
