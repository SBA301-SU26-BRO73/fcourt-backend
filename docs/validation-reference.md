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
| BranchController | [facility-service/src/main/java/com/fcourt/facility/api/BranchController.java](../facility-service/src/main/java/com/fcourt/facility/api/BranchController.java) |
| BranchImageController | [facility-service/src/main/java/com/fcourt/facility/api/BranchImageController.java](../facility-service/src/main/java/com/fcourt/facility/api/BranchImageController.java) |
| BranchOwnerController | [facility-service/src/main/java/com/fcourt/facility/api/BranchOwnerController.java](../facility-service/src/main/java/com/fcourt/facility/api/BranchOwnerController.java) |
| CourtController | [facility-service/src/main/java/com/fcourt/facility/api/CourtController.java](../facility-service/src/main/java/com/fcourt/facility/api/CourtController.java) |
| CourtImageController | [facility-service/src/main/java/com/fcourt/facility/api/CourtImageController.java](../facility-service/src/main/java/com/fcourt/facility/api/CourtImageController.java) |
| LockController | [facility-service/src/main/java/com/fcourt/facility/api/LockController.java](../facility-service/src/main/java/com/fcourt/facility/api/LockController.java) |
| PricingController | [facility-service/src/main/java/com/fcourt/facility/api/PricingController.java](../facility-service/src/main/java/com/fcourt/facility/api/PricingController.java) |
| ReservationController | [facility-service/src/main/java/com/fcourt/facility/api/ReservationController.java](../facility-service/src/main/java/com/fcourt/facility/api/ReservationController.java) |
| TemplateController | [facility-service/src/main/java/com/fcourt/facility/api/TemplateController.java](../facility-service/src/main/java/com/fcourt/facility/api/TemplateController.java) |
| FacilitySecurity | [facility-service/src/main/java/com/fcourt/facility/config/FacilitySecurity.java](../facility-service/src/main/java/com/fcourt/facility/config/FacilitySecurity.java) |
| DeviceController | [notification-service/src/main/java/com/fcourt/notification/device/DeviceController.java](../notification-service/src/main/java/com/fcourt/notification/device/DeviceController.java) |
| DeviceSecurity | [notification-service/src/main/java/com/fcourt/notification/device/DeviceSecurity.java](../notification-service/src/main/java/com/fcourt/notification/device/DeviceSecurity.java) |
| InAppNotificationController | [notification-service/src/main/java/com/fcourt/notification/inapp/InAppNotificationController.java](../notification-service/src/main/java/com/fcourt/notification/inapp/InAppNotificationController.java) |
| AdminRefundController | [payment-service/src/main/java/com/fcourt/payment/AdminRefundController.java](../payment-service/src/main/java/com/fcourt/payment/AdminRefundController.java) |
| BookingExpiryController | [payment-service/src/main/java/com/fcourt/payment/BookingExpiryController.java](../payment-service/src/main/java/com/fcourt/payment/BookingExpiryController.java) |
| BranchLedgerController | [payment-service/src/main/java/com/fcourt/payment/BranchLedgerController.java](../payment-service/src/main/java/com/fcourt/payment/BranchLedgerController.java) |
| CheckoutController | [payment-service/src/main/java/com/fcourt/payment/CheckoutController.java](../payment-service/src/main/java/com/fcourt/payment/CheckoutController.java) |
| CompactPaymentStatusController | [payment-service/src/main/java/com/fcourt/payment/CompactPaymentStatusController.java](../payment-service/src/main/java/com/fcourt/payment/CompactPaymentStatusController.java) |
| CustomerPaymentsController | [payment-service/src/main/java/com/fcourt/payment/CustomerPaymentsController.java](../payment-service/src/main/java/com/fcourt/payment/CustomerPaymentsController.java) |
| FreeVoucherPaymentController | [payment-service/src/main/java/com/fcourt/payment/FreeVoucherPaymentController.java](../payment-service/src/main/java/com/fcourt/payment/FreeVoucherPaymentController.java) |
| PaymentApprovalController | [payment-service/src/main/java/com/fcourt/payment/PaymentApprovalController.java](../payment-service/src/main/java/com/fcourt/payment/PaymentApprovalController.java) |
| PaymentSecurity | [payment-service/src/main/java/com/fcourt/payment/PaymentSecurity.java](../payment-service/src/main/java/com/fcourt/payment/PaymentSecurity.java) |
| PublicRefundController | [payment-service/src/main/java/com/fcourt/payment/PublicRefundController.java](../payment-service/src/main/java/com/fcourt/payment/PublicRefundController.java) |
| ReceiptController | [payment-service/src/main/java/com/fcourt/payment/ReceiptController.java](../payment-service/src/main/java/com/fcourt/payment/ReceiptController.java) |
| RefundController | [payment-service/src/main/java/com/fcourt/payment/RefundController.java](../payment-service/src/main/java/com/fcourt/payment/RefundController.java) |
| TicketController | [payment-service/src/main/java/com/fcourt/payment/TicketController.java](../payment-service/src/main/java/com/fcourt/payment/TicketController.java) |
| UnifiedLedgerController | [payment-service/src/main/java/com/fcourt/payment/UnifiedLedgerController.java](../payment-service/src/main/java/com/fcourt/payment/UnifiedLedgerController.java) |
| PosController | [pos-retail-service/src/main/java/com/fcourt/pos/api/PosController.java](../pos-retail-service/src/main/java/com/fcourt/pos/api/PosController.java) |
| PosSecurity | [pos-retail-service/src/main/java/com/fcourt/pos/config/PosSecurity.java](../pos-retail-service/src/main/java/com/fcourt/pos/config/PosSecurity.java) |
| AnalyticsController | [reporting-service/src/main/java/com/fcourt/reporting/api/AnalyticsController.java](../reporting-service/src/main/java/com/fcourt/reporting/api/AnalyticsController.java) |
| DashboardController | [reporting-service/src/main/java/com/fcourt/reporting/api/DashboardController.java](../reporting-service/src/main/java/com/fcourt/reporting/api/DashboardController.java) |
| ExportController | [reporting-service/src/main/java/com/fcourt/reporting/api/ExportController.java](../reporting-service/src/main/java/com/fcourt/reporting/api/ExportController.java) |
| SuperAdminDashboardController | [reporting-service/src/main/java/com/fcourt/reporting/api/SuperAdminDashboardController.java](../reporting-service/src/main/java/com/fcourt/reporting/api/SuperAdminDashboardController.java) |
| ReportingSecurity | [reporting-service/src/main/java/com/fcourt/reporting/config/ReportingSecurity.java](../reporting-service/src/main/java/com/fcourt/reporting/config/ReportingSecurity.java) |
| HoldController | [slot-hold-engine/src/main/java/com/fcourt/hold/HoldController.java](../slot-hold-engine/src/main/java/com/fcourt/hold/HoldController.java) |
| CheckInController | [staff-operations-service/src/main/java/com/fcourt/staff/api/CheckInController.java](../staff-operations-service/src/main/java/com/fcourt/staff/api/CheckInController.java) |
