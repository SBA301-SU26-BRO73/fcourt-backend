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
- `todayRevenue`: revenue of CONFIRMED bookings dated today in `REPORTING_TIME_ZONE` (booking date, not payment time).
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
- Booking status leaves PENDING; CONFIRMED changes only on `BookingCancelledEvent` (revenue dropped,
  as in the monolith); CANCELLED is final, so reordered/late events cannot regress it. Revenue is recognized once, by the first money event (confirmation or cash
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

## Revenue export (SCRUM-76) — ADMIN only

`GET /api/admin/dashboard/export/revenue?fromDate=2026-10-01&toDate=2026-10-31&format=xlsx|csv&branchId={id}`

- Returns the file as an attachment (`Content-Disposition: attachment; filename="bao-cao-doanh-thu_20261001_20261031.xlsx"`,
  `Cache-Control: no-store`). The HTTP response streams rows as they are read; XLSX uses `SXSSFWorkbook`, CSV writes
  incrementally as UTF-8 with BOM. The read query uses a read-only transaction and JDBC cursor fetches
  so Excel shows Vietnamese correctly.
- Same owner scoping as the dashboard (facility-service decides the branches; foreign `branchId` -> 403, facility down -> 503).
- Rows: CONFIRMED bookings with *play date* in `[fromDate, toDate]`, columns Mã đơn / Chi nhánh / Ngày chơi / Ngày ghi nhận
  doanh thu / Số slot / Số tiền (VND) / Trạng thái, then the net revenue line. Amounts are the recognized `revenue` the
  dashboard sums (CONFIRMED, by booking date), so totals match it.
- 400: `fromDate`/`toDate` missing or malformed, `fromDate > toDate`, range over 366 days, `format` not xlsx/csv, `branchId <= 0`.
- The current export still omits court and customer names required by SCRUM-76; add them after the analytics read-model
  court fields from SCRUM-43 are merged and customer name is projected from payment events.

## Super Admin overview (SCRUM-78) — SUPER_ADMIN only

`GET /api/super-admin/dashboard/overview?months=12` (1..24; default 12)

```json
{"owners":{"active":3,"pending":2,"rejected":1,"total":6},"branches":{"total":4,"active":3},"courts":10,
 "customers":{"total":40,"active":35},"gmv":5000000,"saasRevenue":900000,
 "monthly":[{"month":"2026-10","newAccounts":9,"gmv":1000000,"saasRevenue":600000}],
 "topOwners":[{"ownerId":10,"ownerName":"Chủ A","revenue":1500000,"branches":2}],"generatedAt":"2026-11-05T03:00:00Z"}
```

- `gmv` = CONFIRMED booking revenue (read model, all branches); `saasRevenue` = PAID plan purchases (subscription-service).
  Different sources, never summed together.
- Sources: facility-service `GET /api/admin/branches` (all branches for SUPER_ADMIN), auth-service
  `GET /api/super-admin/stats/accounts|owner-names`, subscription-service `GET /api/super-admin/stats/saas-revenue`;
  the caller's token is forwarded. Any source down -> 503 (no zero-filled figures). New env: `AUTH_SERVICE_URL`
  (`http://localhost:8081`), `SUBSCRIPTION_URL` (`http://localhost:8082`).
