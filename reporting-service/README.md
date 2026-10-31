# reporting-service

Owner dashboard statistics from a local read model fed by Kafka (SCRUM-12).
Spring Boot 3.5, Java 21, JdbcTemplate + Flyway on `reporting_db`, port **8084**.

## Run locally

```powershell
$env:REPORTING_DB_URL = 'jdbc:postgresql://localhost:5432/reporting_db'
$env:AUTH_JWK_SET_URI = 'http://localhost:8081/.well-known/jwks.json'
$env:FACILITY_URL = 'http://localhost:8083'
.\mvnw.cmd spring-boot:run
```

| Variable | Default |
|---|---|
| `SERVER_PORT` | `8084` |
| `REPORTING_DB_URL` / `REPORTING_DB_USER` / `REPORTING_DB_PASSWORD` | `jdbc:postgresql://localhost:5432/reporting_db` / `postgres` / `postgres` |
| `AUTH_JWK_SET_URI` / `AUTH_JWT_ISSUER` / `AUTH_JWT_AUDIENCE` | `http://localhost:8081/.well-known/jwks.json` / `http://localhost:8081` / `fcourt` |
| `FACILITY_URL` | `http://localhost:8083` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `REPORTING_CONSUMER_GROUP` | `reporting-service-v1` |
| `PAYMENT_EVENTS_TOPIC` / `STAFF_EVENTS_TOPIC` / `BRANCH_UPDATED_TOPIC` | `fcourt.payment.events` / `fcourt.staff.events` / `facility.branch-updated.v1` |
| `REPORTING_KAFKA_ENABLED` | `true` |
| `REPORTING_TIME_ZONE` | `Asia/Ho_Chi_Minh` |

## API

`GET /api/admin/dashboard/stats?branchId={id}` — ADMIN only (RS256 JWT, same rules as the other
services). `branchId` omitted = aggregate over all branches of the caller.

```json
{"branchId":7,"totalRevenue":160000,"todayRevenue":160000,"totalBookings":4,
 "bookingStatusCounts":{"CONFIRMED":2,"PENDING":1,"CANCELLED":1},
 "activeCourts":1,"totalCourts":1,"occupancyRate":0.0938,"generatedAt":"2026-10-31T10:00:00Z"}
```

- **Scoping (no mixing between owners)**: the branch set comes from facility-service with the
  caller's Bearer token (`GET /api/admin/branches/{id}` or `GET /api/admin/branches`) and is
  re-checked against `sub`; every read-model query is filtered by exactly those ids. Another
  owner's branch → 403; facility unavailable → 503.
- `totalRevenue`: recognized revenue (confirmed online payments + cash walk-ins), all time.
- `todayRevenue`: revenue recognized today in `REPORTING_TIME_ZONE`.
- `bookingStatusCounts`: PENDING (payment submitted), CONFIRMED (payment confirmed, walk-in,
  checked-in, completed), CANCELLED (payment rejected, unpaid hold expired).
- `activeCourts`: courts in the latest `facility.branch-updated.v1` snapshot (0 for inactive/deleted
  branches); `totalCourts`: `courtCount` returned by facility.
- `occupancyRate`: today's confirmed 30-minute slots ÷ (active courts × half-hours between the
  branch's open and close time), 0..1, 4 decimals.

The stats query is a few indexed aggregates on the read model plus one facility call
(well under 150 ms locally).

## Events consumed (consumer group `reporting-service-v1`)

| Topic | Producer | Used types |
|---|---|---|
| `fcourt.payment.events` | payment-service | `PaymentSubmittedEvent`, `PaymentConfirmedEvent`, `PaymentRejectedEvent`, `BookingCancelledEvent` (`{eventId,type,payment:{bookingId,branchId,amount,checkout:{date,slots}}}`) |
| `fcourt.staff.events` | staff-operations-service | `WalkInBookingCreatedEvent`, `BookingCheckedInEvent`, `BookingCompletedEvent` (`{eventId,type,version,data:{booking,...}}`) |
| `facility.branch-updated.v1` | facility-service | full branch snapshot (version guarded) |

- Deduplication: `processed_events(event_id)` is written in the same transaction as the fact,
  so redelivered events are ignored. Branch snapshots use the per-branch version instead.
- Booking status only leaves PENDING; CONFIRMED/CANCELLED are final, so reordered/late events
  cannot regress it. Revenue is recognized once, by the first money event (confirmation or cash
  walk-in; payment confirmation uses the Kafka record timestamp because the payload has no
  confirmation time).
- Unknown event types are recorded and skipped. Invalid records go straight to `<topic>.DLT`;
  other failures retry twice (1 s) before the DLT. Offsets are committed per record.

**booking-service has no implementation or events yet.** Bookings are therefore derived from
payment and staff events; online bookings that never reach checkout are not counted. When
booking-service publishes its events, add them to `EventTranslator` (same dedupe/precedence).

## Tests

`.\mvnw.cmd clean verify` (Java 21, Docker running): `EventTranslatorTest` (envelope mapping and
validation) and `ReportingIntegrationTest` (PostgreSQL 16 Testcontainer + embedded Kafka: events →
read model → stats, duplicate delivery, older snapshot ignored, owner scoping 403, DLT, 400/401/403).
