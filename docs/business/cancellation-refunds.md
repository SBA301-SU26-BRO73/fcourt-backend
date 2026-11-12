Paid cancellation refunds (A6 / A12)
===================================

Owner cancellations keep the existing 100% policy. Guest cancellations keep the existing cutoff and configured refund percentage. Unpaid bookings and zero refund entitlements do not create refund requests.

The booking outbox's existing `BookingCancelledEvent` now carries additive `totalPrice` and `customerPhone` fields. Payment-service consumes guest cancellations (`customerId: null`) and owner cancellations (`cancelledByRole: ADMIN`) on `fcourt.booking.events`, group `payment-cancellation-refunds`. Signed-in customer cancellations continue using their existing request API. Older cancellation events without the new fields are accepted; an absent customerId alone is not treated as proof of a guest booking.

All refund creators share the customer flow's request insert and `RefundRequestedEvent`. Creation is asynchronous after cancellation (the booking outbox normally polls every four seconds). The public submission API can create the same request immediately if the event has not arrived. All writers use the same PostgreSQL advisory transaction lock and the existing UNIQUE constraint on `refund_requests.booking_id`. Event redelivery or a simultaneous submission creates neither another refund nor another notification, and never overwrites submitted bank details or a decision. Requested/completed events retain their existing envelope and allow a null guest customerId; bank details and phone are not included in payment notification events.

Public API
----------

Both new endpoints require no JWT. Every call verifies booking code + phone through booking-service's existing guest-only public lookup, including code normalization and Vietnamese phone normalization. Account bookings, incorrect phones, and unknown codes return the same `404 Booking not found`. No client-supplied booking id, refund id, branch id, amount, or status is used. Both responses use `Cache-Control: no-store`.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/public/refunds` | Submit or update bank details; create the eligible request if its cancellation event is still pending. |
| POST | `/api/public/refunds/lookup` | Read the verified guest booking's refund state. Does not create a request. |

Submission example:

```http
POST /api/public/refunds
Content-Type: application/json

{
  "bookingCode": "FC12345678",
  "phone": "0901234567",
  "bankName": "Vietcombank",
  "accountNumber": "00123456789",
  "accountHolder": "NGUYEN VAN A"
}
```

`bankBin` is optional; when supplied it must match `970[0-9]{3}`. The three displayed bank fields are required: bank name at most 100 characters, account number 6–20 digits, account holder 2–150 characters. Use `accountNumber` and `accountHolder` in this public request; the response retains the existing refund field names `bankAccountNumber` and `bankAccountName`.

Successful create/update/retry returns HTTP 200, for example:

```json
{
  "id": 25,
  "bookingId": 42,
  "amount": 100000,
  "status": "PENDING",
  "bankName": "Vietcombank",
  "bankBin": null,
  "bankAccountNumber": "00123456789",
  "bankAccountName": "NGUYEN VAN A",
  "reason": "Guest cancellation refund",
  "adminNote": null,
  "transferProofUrl": null,
  "createdAt": "2026-11-12T01:00:00Z",
  "updatedAt": "2026-11-12T01:00:00Z",
  "customerPhone": "0901234567",
  "bankInfoMissing": false
}
```

Lookup request:

```http
POST /api/public/refunds/lookup
Content-Type: application/json

{"bookingCode":"FC12345678","phone":"0901234567"}
```

HTTP 200 response, before the event is consumed:

```json
{"bookingId":42,"amount":100000,"eligible":true,"request":null}
```

After creation, `request` contains the refund object above. An automatic request initially has null bank fields, `status: PENDING`, and `bankInfoMissing: true`. Public `eligible` means bank submission is allowed: true before request creation or while PENDING, false after APPROVED/REJECTED/COMPLETED. Changing bank details after PENDING returns 409; an identical retry returns the existing request without mutation even after a decision.

Other errors: 400 invalid field format; 409 not cancelled, unpaid, zero entitlement, inconsistent payment identity/amount, or bank changes after PENDING; 503 booking authority unavailable or invalid response; 429 gateway rate limit. Both new routes share the existing per-IP `guest-lookup` budget with booking lookup and cancellation (default five requests per minute, burst five), plus the general limit. Respect `Retry-After` and avoid rapid polling.

Web handoff
-----------

Frontend reference was read only with `git -C ../fcourt-web show origin/dev:<path>`; no frontend files were changed.

1. Add nullable `branchId` and `refundAmount` to the guest booking response type. The existing `POST /api/public/bookings/lookup` returns these additive fields; `refundAmount` is null before cancellation. Keep the verified code and phone already stored by the `/lookup` page.
2. After guest cancellation, use its returned `refundAmount`; on a later lookup use the booking response's `refundAmount`. Show the refund section when status is CANCELLED and amount is positive.
3. Read current request/state with `POST /api/public/refunds/lookup` and the verified code + phone. `request: null` means the cancellation event may still be pending; the bank form can already submit. Display status and amount when a request exists, and prefill saved bank fields while PENDING.
4. Submit the form with `POST /api/public/refunds` and the exact five required fields in the example. Show the returned PENDING request and allow corrections while PENDING. Explain 404 without distinguishing code/phone/account, display 409, and honor the shared 429 budget.
5. Add `bankInfoMissing: boolean` and `customerPhone: string | null` to refund types. Bank fields remain nullable. In the owner refund list, show missing-bank requests and their phone so the owner can contact the customer/guest; disable approval/completion while bank information is missing. Required bank information means name + account number + holder; optional bankBin does not make information missing.
6. For an owner-cancelled account booking, the existing customer `GET /api/refunds/booking/{bookingId}` returns the automatic request and `eligible: true` while it is PENDING with missing banks. Let the existing customer form submit `POST /api/refunds/request` to fill it (that API still requires bankBin and reason). The refund id, amount, and notification remain unchanged.

Existing cancellation/admin endpoints retain their contracts: `POST /api/public/bookings/cancel` (code + phone), `POST /api/admin/bookings/{id}/cancel` (ADMIN and branch ownership), `GET /api/admin/refunds/pending?branchId=7` (ADMIN and branch ownership), and refund approve/reject/complete routes. PENDING requests with missing banks appear in the owner list. Approval returns 409 until banks are supplied; the existing proof upload and APPROVED/COMPLETED lifecycle then apply.

Migration and rollout
---------------------

Payment Flyway `V9__automatic_cancellation_refunds.sql` drops NOT NULL from `refund_bank_name`, `refund_bank_account_number`, and `refund_bank_account_name`, and adds nullable `customer_phone varchar(32)`. No new uniqueness migration is needed: V1 already defines `refund_requests_booking_id_key UNIQUE (booking_id)`. `bankInfoMissing` is computed from the bank fields rather than stored independently.

Apply payment's migration with the new payment-service release; release booking-service's additive lookup/event fields before exposing the public refund form. Keep payment events enabled. Automatic queue creation depends on Kafka/outbox delivery. The new consumer can process older retained cancellation events without the additive fields, but the migration does not backfill bookings whose events are no longer retained. Older events without a contact phone may create a refund with `customerPhone: null`.

Validation
----------

Only booking-service, payment-service, and gateway-service were built with their own Maven wrappers. Docker-dependent tests were excluded from execution but **all test sources were compiled**. The `OpenApiDocsTest` classes also require Docker through static initializers, so those are excluded too. Final results: booking 50 passed; payment 55 passed; gateway 149 passed; no failures or errors. PostgreSQL/Flyway execution and actual concurrent PostgreSQL locking were not run locally.

Commands, from each module directory on Windows:

```powershell
# booking-service
.\mvnw.cmd test "-Dtest=!AdminBookingApiTest,!AdminBookingCancelApiTest,!BookingApiTest,!BookingCancellationTest,!FullCourtReservationTest,!GuestBookingCancelApiTest,!GuestBookingLookupApiTest,!OnlineBookingTest,!OpenApiDocsTest,!SlotRedisLatencyIntegrationTest,!SlotWebSocketIntegrationTest,!StaffAdmissionsTest,!StaffCounterTest"
# payment-service
.\mvnw.cmd test "-Dtest=!PaymentDatabaseTest,!S3ReceiptStorageContainerTest,!OpenApiDocsTest"
# gateway-service
.\mvnw.cmd test
```

New Docker-free coverage checks guest policy/owner full-refund cancellation events, unpaid cases, guest event compatibility, null customer IDs, one request/notification on redelivery or event/submission ordering, server-side entitlement checks, guest verification and invalid identities, protected bank updates, owner pending/contact visibility, approval guards, guest completion events, public request validation/security/cache headers, and shared gateway rate limits. Added PostgreSQL ITs cover event/submission concurrency and automatic creation rollback on outbox failure; they compile but require Docker to execute.

Changed files
-------------

- Booking production: `booking-service/src/main/java/com/fcourt/booking/BookingCancellation.java`, `dto/response/GuestBookingResponse.java`, `service/GuestBookingLookupService.java` (the latter two relative to that Java package).
- Booking tests: `booking-service/src/test/java/com/fcourt/booking/CancellationRefundContractTest.java`, `GuestBookingLookupApiTest.java`.
- Payment production, under `payment-service/src/main/java/com/fcourt/payment/`: `BookingRefundConsumer.java`, `PublicRefundController.java`, `RefundService.java`, `RefundView.java`, `PaymentUpstreams.java`, `AdminRefundService.java`, `ReceiptUrls.java`.
- Payment migration: `payment-service/src/main/resources/db/migration/V9__automatic_cancellation_refunds.sql`.
- Payment tests, under `payment-service/src/test/java/com/fcourt/payment/`: `AutomaticRefundServiceTest.java`, `BookingRefundConsumerTest.java`, `PublicRefundApiTest.java`, `GuestRefundAuthorityTest.java`, `GuestRefundLifecycleTest.java`, `PaymentDatabaseTest.java`, `AdminRefundProofCleanupTest.java`, `S3ReceiptStorageTest.java`.
- Gateway: `gateway-service/src/main/resources/application.yml`, `gateway-service/src/test/java/com/fcourt/gateway/GatewaySecurityTests.java`, `RateLimitGatewayTests.java`.
- Handoff: `docs/business/cancellation-refunds.md`.
