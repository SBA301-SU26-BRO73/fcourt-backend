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
