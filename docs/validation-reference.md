# Validation and contract reference

Use this index to locate service contracts, review notes, validation records,
and test sources. Check the date, revision, environment, and limitations in each
record before using it as evidence for the current deployment. Historical
validation records do not replace a fresh test run after implementation changes.

Python links point to test sources. The protobuf link defines a communication
contract. Follow the owning component's README for execution and build commands.

Java links below locate API, security, and configuration implementations
for tracing requests during review. These source references are navigation
aids and do not establish runtime validation results.

| Reference | Source |
| --- | --- |
| discovery review notes | [discovery-service/REQUEST_REVIEW.md](../discovery-service/REQUEST_REVIEW.md) |
| discovery validation notes | [discovery-service/VALIDATION.md](../discovery-service/VALIDATION.md) |
| pos integration contracts | [pos-retail-service/CONTRACTS.md](../pos-retail-service/CONTRACTS.md) |
| pos validation notes | [pos-retail-service/VALIDATION.md](../pos-retail-service/VALIDATION.md) |
| staff integration contracts | [staff-operations-service/CONTRACTS.md](../staff-operations-service/CONTRACTS.md) |
| staff review notes | [staff-operations-service/REQUEST_REVIEW.md](../staff-operations-service/REQUEST_REVIEW.md) |
| staff validation notes | [staff-operations-service/VALIDATION.md](../staff-operations-service/VALIDATION.md) |
| staff scrum-28 evidence | [staff-operations-service/VALIDATION_SCRUM_28.md](../staff-operations-service/VALIDATION_SCRUM_28.md) |
| staff scrum-30 evidence | [staff-operations-service/VALIDATION_SCRUM_30.md](../staff-operations-service/VALIDATION_SCRUM_30.md) |
| staff scrum-61 evidence | [staff-operations-service/VALIDATION_SCRUM_61.md](../staff-operations-service/VALIDATION_SCRUM_61.md) |
| staff scrum-63 evidence | [staff-operations-service/VALIDATION_SCRUM_63.md](../staff-operations-service/VALIDATION_SCRUM_63.md) |
| staff scrum-65 evidence | [staff-operations-service/VALIDATION_SCRUM_65.md](../staff-operations-service/VALIDATION_SCRUM_65.md) |
| demo seed reassessment | [tools/demo_seed/REASSESSMENT.md](../tools/demo_seed/REASSESSMENT.md) |
| demo seed tests | [tools/demo_seed/test_seed.py](../tools/demo_seed/test_seed.py) |
| production smoke evidence | [tools/production_smoke/LIVE_VALIDATION.md](../tools/production_smoke/LIVE_VALIDATION.md) |
| production smoke tests | [tools/production_smoke/test_smoke.py](../tools/production_smoke/test_smoke.py) |
| system validation evidence | [tools/system_tests/LIVE_VALIDATION.md](../tools/system_tests/LIVE_VALIDATION.md) |
| system reassessment notes | [tools/system_tests/REASSESSMENT.md](../tools/system_tests/REASSESSMENT.md) |
| system flow tests | [tools/system_tests/test_flow.py](../tools/system_tests/test_flow.py) |
| booking slot safety contract | [booking-service/src/main/proto/slot_safety.proto](../booking-service/src/main/proto/slot_safety.proto) |
| AuthController | [auth-service/src/main/java/com/fcourt/auth/account/AuthController.java](../auth-service/src/main/java/com/fcourt/auth/account/AuthController.java) |
| AvatarController | [auth-service/src/main/java/com/fcourt/auth/account/AvatarController.java](../auth-service/src/main/java/com/fcourt/auth/account/AvatarController.java) |
| GoogleLoginController | [auth-service/src/main/java/com/fcourt/auth/account/GoogleLoginController.java](../auth-service/src/main/java/com/fcourt/auth/account/GoogleLoginController.java) |
| PasswordController | [auth-service/src/main/java/com/fcourt/auth/account/PasswordController.java](../auth-service/src/main/java/com/fcourt/auth/account/PasswordController.java) |
| StaffAccountController | [auth-service/src/main/java/com/fcourt/auth/staff/StaffAccountController.java](../auth-service/src/main/java/com/fcourt/auth/staff/StaffAccountController.java) |
| PlatformStatsController | [auth-service/src/main/java/com/fcourt/auth/superadmin/PlatformStatsController.java](../auth-service/src/main/java/com/fcourt/auth/superadmin/PlatformStatsController.java) |
| SuperAdminController | [auth-service/src/main/java/com/fcourt/auth/superadmin/SuperAdminController.java](../auth-service/src/main/java/com/fcourt/auth/superadmin/SuperAdminController.java) |
| JwksController | [auth-service/src/main/java/com/fcourt/auth/token/JwksController.java](../auth-service/src/main/java/com/fcourt/auth/token/JwksController.java) |
| AdminBookingController | [booking-service/src/main/java/com/fcourt/booking/AdminBookingController.java](../booking-service/src/main/java/com/fcourt/booking/AdminBookingController.java) |
| BookingCancellationController | [booking-service/src/main/java/com/fcourt/booking/BookingCancellationController.java](../booking-service/src/main/java/com/fcourt/booking/BookingCancellationController.java) |
| BookingController | [booking-service/src/main/java/com/fcourt/booking/BookingController.java](../booking-service/src/main/java/com/fcourt/booking/BookingController.java) |
| BookingSecurity | [booking-service/src/main/java/com/fcourt/booking/BookingSecurity.java](../booking-service/src/main/java/com/fcourt/booking/BookingSecurity.java) |
| BookingVoucherController | [booking-service/src/main/java/com/fcourt/booking/BookingVoucherController.java](../booking-service/src/main/java/com/fcourt/booking/BookingVoucherController.java) |
| StaffAdmissionController | [booking-service/src/main/java/com/fcourt/booking/StaffAdmissionController.java](../booking-service/src/main/java/com/fcourt/booking/StaffAdmissionController.java) |
| StaffCounterController | [booking-service/src/main/java/com/fcourt/booking/StaffCounterController.java](../booking-service/src/main/java/com/fcourt/booking/StaffCounterController.java) |
| GuestBookingCancelController | [booking-service/src/main/java/com/fcourt/booking/controller/GuestBookingCancelController.java](../booking-service/src/main/java/com/fcourt/booking/controller/GuestBookingCancelController.java) |
| GuestBookingLookupController | [booking-service/src/main/java/com/fcourt/booking/controller/GuestBookingLookupController.java](../booking-service/src/main/java/com/fcourt/booking/controller/GuestBookingLookupController.java) |
| DiscoverySecurity | [discovery-service/src/main/java/com/fcourt/discovery/config/DiscoverySecurity.java](../discovery-service/src/main/java/com/fcourt/discovery/config/DiscoverySecurity.java) |
| BranchController | [discovery-service/src/main/java/com/fcourt/discovery/controller/BranchController.java](../discovery-service/src/main/java/com/fcourt/discovery/controller/BranchController.java) |
| CourtSlotController | [discovery-service/src/main/java/com/fcourt/discovery/controller/CourtSlotController.java](../discovery-service/src/main/java/com/fcourt/discovery/controller/CourtSlotController.java) |
| CourtSlotInternalController | [discovery-service/src/main/java/com/fcourt/discovery/controller/CourtSlotInternalController.java](../discovery-service/src/main/java/com/fcourt/discovery/controller/CourtSlotInternalController.java) |
| ReviewController | [discovery-service/src/main/java/com/fcourt/discovery/controller/ReviewController.java](../discovery-service/src/main/java/com/fcourt/discovery/controller/ReviewController.java) |
| BookingContextController | [facility-service/src/main/java/com/fcourt/facility/api/BookingContextController.java](../facility-service/src/main/java/com/fcourt/facility/api/BookingContextController.java) |
