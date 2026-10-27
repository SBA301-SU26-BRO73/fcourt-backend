# Payment service — SCRUM-32

Spring Boot 3 / Java 21, port **8089**, PostgreSQL database **payment_db**.

```sh
# From fcourt-backend
docker compose -f payment-service/compose.yml up -d
mvn -f payment-service/pom.xml spring-boot:run
curl http://localhost:8089/actuator/health
```

Local database uses `localhost:5489`, user/password `postgres`. Set
`PAYMENT_DB_PASSWORD` for Compose and the corresponding `DATABASE_PASSWORD`
for the service when changing credentials. Configuration:

| Variable | Default |
|---|---|
| SERVER_PORT | 8089 |
| DATABASE_URL | jdbc:postgresql://localhost:5489/payment_db |
| DATABASE_USER / DATABASE_PASSWORD | postgres / postgres (local only) |
| BOOKING_URL | http://localhost:8088 |
| FACILITY_URL | http://localhost:8083 |

Flyway initializes an empty payment database with a JDBC-compatible version of
the existing `database/V1__init_schema.sql`. It retains `payments` and
`refund_requests` and their existing status constraints. This dedicated Compose
database is initialized by Flyway; it does not run the legacy psql init script
again. Existing populated databases require an explicit reviewed Flyway baseline
after schema comparison; automatic baselining is deliberately disabled.

## Checkout endpoint

`GET /api/payments/checkout-info/{bookingId}` requires either an
`Authorization: Bearer ...` header for signed-in customers or `X-Hold-Token` for
guests. Credentials are forwarded **only to booking-service**, which must enforce
booking ownership and current hold validity. An absent credential returns 401
before any upstream call. Successful responses use `Cache-Control: no-store`.

```json
{
  "bookingId":42,"bookingCode":"BK00000042","branchId":7,
  "branchName":"Thu Duc","courtName":"Court 1","date":"2030-01-01",
  "slots":[{"startTime":"18:00:00","endTime":"18:30:00","price":75000}],
  "amount":75000,"bankName":"Vietcombank","bankBin":"970436",
  "bankAccountNumber":"00123456789","bankAccountName":"FCOURT DEMO",
  "transferContent":"FC BK00000042","expiresAt":"2030-01-01T00:05:00Z"
}
```

The amount comes from the booking server's `totalPrice`, including any applicable
pricing/discount calculation. It uses BigDecimal and must be positive whole VND;
invalid fractions are rejected rather than rounded. Account numbers stay strings
so leading zeros survive. The unique booking code supplies the deterministic
transfer content. This GET does not create a payment or mutate booking state.

Errors: 400 invalid ID; 401/403 rejected credentials; 404 booking absent; 410
hold expired/cancelled; 409 booking already submitted/paid or otherwise not
`PENDING_PAYMENT`; 503 malformed/unavailable upstream or missing bank setup.
Expiration is checked again after loading bank details to reject sessions that
expire while the upstream calls are in flight.

## Proposed upstream contracts — integration pending

Booking-service must implement `GET /api/bookings/{bookingId}`, authenticate the
forwarded credentials against that booking and, for a guest, validate all Redis
keys associated with its hold token. Its JSON response must contain:

```json
{
  "id":42,"bookingCode":"BK00000042","branchId":7,
  "branchName":"Thu Duc","courtName":"Court 1","date":"2030-01-01",
  "slots":[{"startTime":"18:00:00","endTime":"18:30:00","price":75000}],
  "totalPrice":75000,"status":"PENDING_PAYMENT",
  "holdExpiresAt":"2030-01-01T00:05:00Z"
}
```

Facility-service must expose `GET /api/public/branches/{branchId}` with `id`,
`bankName`, `bankAccountNumber`, `bankAccountName` and six-digit `bankBin` (needed
for VietQR in SCRUM-33). Requests never forward guest/customer credentials to it.
The returned branch ID must match the booking's branch.

These APIs and booking creation are absent from current `dev`. SCRUM-14 is in
progress, SCRUM-24 is awaiting review, and booking-service still contains SQL
only. The proposed shapes are validated with HTTP-boundary stubs; actual API
integration, guest authorization and hold-to-booking creation are not validated
end to end. Do not use this service as a standalone authorization authority.

Receipt upload/events (SCRUM-34), admin approval (SCRUM-36), ticket QR and
auto-cancel (SCRUM-38) are separate tasks and not included in this PR.

## Checks

```sh
mvn -f payment-service/pom.xml clean verify
```

Tests cover credential forwarding, 404, 410 (including expiration in flight),
state conflicts, decimal money, leading-zero accounts, malformed dependencies,
branch mismatch and fail-closed behavior. A PostgreSQL 16 Testcontainer starts
the full Spring application, applies Flyway and verifies decimal persistence.

## Receipt submission — SCRUM-34

`POST /api/payments/{bookingId}/upload-receipt`, multipart part `file`, accepts decoded JPEG/PNG up to 5 MiB / 25 megapixels. Supply `Authorization: Bearer ...` or `X-Hold-Token` in headers. The booking authority must authorize the caller before any existing payment is disclosed. A retry returns the existing payment and does not replace the receipt. `GET /api/payments/{bookingId}/status` uses the same authorization and returns `id`, `bookingId`, `status` (`PENDING`, `CONFIRMED`, `REJECTED`), `receiptImageUrl`, `submittedAt`, `rejectionReason`, and the server checkout snapshot. All responses use `Cache-Control: no-store`.

Set `CLOUDINARY_URL` through the environment (signed SDK credentials); missing storage configuration returns 503. Credentials and caller tokens are never saved in the database or emitted in events. The SDK validates HTTPS Cloudinary delivery URLs. Receipt images use unguessable public IDs; configure the storage account's access and retention policy before production use. Aborted/concurrent uploads may leave unreferenced images, which require a storage retention job.

Submission commits the payment and `PaymentSubmittedEvent` to `payment_outbox` in one PostgreSQL transaction. The relay publishes to `fcourt.payment.events`, keyed by booking ID, with `eventId`, `type`, and `payment`. `KAFKA_BOOTSTRAP_SERVERS` defaults to localhost:9092. Acknowledged events are marked published; broker failure preserves pending rows. At-least-once delivery requires consumers to deduplicate `eventId`. One advisory lock preserves publication order across instances. Disable only the relay in tests with `PAYMENT_EVENTS_ENABLED=false`.

**Integration contract:** booking-service consumes `PaymentSubmittedEvent` and atomically changes an eligible `PENDING_PAYMENT` booking to `AWAITING_CONFIRMATION`, retaining occupied slots. This consumer and the actual booking/facility APIs are not implemented on `dev`; payment-service never writes their databases. Receipt persistence alone does not establish this cross-service acceptance condition. Coordinate booking expiry against receipt submission at this boundary before enabling customer traffic.

Validation: Java 21 `mvn -f payment-service/pom.xml clean verify` (18 tests, PostgreSQL 16 Testcontainer); mocks isolate Cloudinary/upstream HTTP and Kafka acknowledgement/failure. Real Cloudinary credentials and live booking/facility/Kafka acceptance remain external checks. SDK references: [Cloudinary Java](https://cloudinary.com/documentation/java_integration), [Spring Kafka sending](https://docs.spring.io/spring-kafka/reference/kafka/sending-messages.html).

## Admin approval — SCRUM-36

- `GET /api/admin/payments/pending?branchId=7&offset=0`: oldest-first submitted PENDING records, 50 per page. Snapshot includes optional `customerName` supplied by the booking authority; absent names display as guest/customer in Flutter.
- `PUT /api/admin/payments/{paymentId}/confirm`: commits local payment `CONFIRMED` and `PaymentConfirmedEvent`.
- `PUT /api/admin/payments/{paymentId}/reject`: JSON `{"reason":"..."}` (1–500 nonblank characters), commits `REJECTED`, reason and `PaymentRejectedEvent`.

Admin routes validate RS256 JWT signatures through `AUTH_JWK_SET_URI`, exact `AUTH_JWT_ISSUER`, audience `AUTH_JWT_AUDIENCE` (default `fcourt`), expiration and `roles: ["ADMIN"]` (or `role: "ADMIN"`). `sub` must contain the positive numeric user ID. Configure these against the final auth-service contract; no unsigned/decoded-only token fallback exists. Customer/guest routes continue delegating booking ownership to the authoritative booking API.

Every admin read/write calls `GET facility /api/admin/branches/{id}` with the caller Bearer credential and requires response `{ "id":7, "adminId":9 }` to match JWT subject. This proposed endpoint must enforce active account/branch ownership in facility-service. Missing/invalid dependencies fail closed (503); foreign owners get 403. Payment IDs, branch IDs and actor IDs are never trusted from a client decision body.

Same-decision retries return existing data without duplicate events; opposite decisions return 409. A per-booking transaction lock and row lock serialize confirm/reject. Payment state and outbox event commit or roll back together. The inherited schema uses `CONFIRMED` for a successful payment, rather than introducing a second `SUCCESS` spelling. Booking consumer contract: confirmation atomically enters booking `CONFIRMED`; rejection enters `CANCELLED` and releases slots. Consumers must deduplicate event IDs and handle races with booking expiry; these cross-service side effects remain pending until those consumers are implemented.

Validation: Java 21 `mvn -f payment-service/pom.xml clean verify` — 26 tests. Adds actual RSA-signed JWT/JWK checks (invalid signature/issuer/audience/expiry), Spring Security 401/403, facility ownership mismatch, PostgreSQL confirm/reject race, idempotency, reason validation and forced-outbox-failure rollback. No live auth/facility/booking integration is claimed.

## Check-in tickets and checkout expiry — SCRUM-38

Checkout GET now persists an unsubmitted session in `payment_db` with the **original server expiration**; repeated requests do not restart the five-minute hold. Receipt submission upgrades that same record. Status returns a minimal receipt DTO (id, bookingId, status, receiptImageUrl, submittedAt, rejectionReason), excluding ticket secrets and the bank snapshot. Unsubmitted PENDING sessions return 404 from receipt status so Flutter still offers upload.

Confirmation generates and persists a unique 32-digit cryptographically random check-in code (~106 bits) and a relative `qrImageUrl` (`/api/payments/{id}/ticket/qr`) in the same transaction as the decision/event. Reconfirming returns the same code. A previously confirmed legacy record without a code is backfilled by a repeated confirm and emits the updated confirmation event. QR generation uses ZXing and includes the raw numeric code only.

`GET /api/payments/{bookingId}/ticket` returns bookingCode, branchName, courtName, date, slots, checkInCode and qrImageUrl. `GET .../ticket/qr` returns a 600×600 PNG with quiet zone. Both require the original booking owner/guest credential in headers, verify authoritative booking status is CONFIRMED / CHECKED_IN / COMPLETED and local payment CONFIRMED, and send no-store. Pending booking synchronization returns 409; cancelled bookings return 410. Never put hold/Bearer credentials in a URL or send them to external QR providers.

The expiry scheduler runs every 60 seconds, in indexed batches of 200 sessions. It checks `expires_at <= now` and, under the same per-booking lock used by upload/approval, changes only **unsubmitted PENDING** sessions to CANCELLED and atomically enqueues BookingCancelledEvent. Submitted/confirmed/rejected sessions are exempt. An expired upload returns 410 even if the sweep has not yet run. Sweeping has up to one interval of latency (and longer for a backlog); the booking authority must independently reject expired holds immediately. Holds never sent to checkout remain the responsibility of booking-service / slot-hold-engine.

**Cross-service acceptance:** booking-service consumes PaymentConfirmedEvent (including checkInCode and qrImageUrl) to persist ticket data and confirm the booking; consumes rejection/expiry to cancel and release slots, with idempotent state guards. This service only stores its local copy and emits durable events. It does not directly mutate booking_db or claim unseen booking/slot consumers are implemented. A production rollout needs agreed receipt-vs-expiry coordination and these consumers; owner/guest credentials must remain valid for reading a paid booking after the original hold expires.

Validation: Java 21 `mvn -f payment-service/pom.xml clean verify` — 31 tests including PostgreSQL 16, exact five-minute expiry, submitted-session exemption, sweep/upload race, stable unique code, actual ZXing QR decode, ticket ownership/state gating and no-store PNG APIs. Existing tests cover transactional outbox rollback and real RSA signature validation. Flutter consumes this contract in SCRUM-39. Backend repo has no CI workflow.
