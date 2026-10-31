# facility-service

Branches, courts and 30-minute time slot templates of court owners (SCRUM-14, SCRUM-16, SCRUM-18).
Spring Boot 3.5, Java 21, JdbcTemplate + Flyway on `facility_db`, port **8083**.

## Run locally

Needs PostgreSQL with a `facility_db` database, Kafka, and a JWKS endpoint (auth-service, or the
dev token tool used by the team).

```powershell
$env:FACILITY_DB_URL = 'jdbc:postgresql://localhost:5432/facility_db'
$env:AUTH_JWK_SET_URI = 'http://localhost:8081/.well-known/jwks.json'
.\mvnw.cmd spring-boot:run
```

| Variable | Default |
|---|---|
| `SERVER_PORT` | `8083` |
| `FACILITY_DB_URL` / `FACILITY_DB_USER` / `FACILITY_DB_PASSWORD` | `jdbc:postgresql://localhost:5432/facility_db` / `postgres` / `postgres` |
| `AUTH_JWK_SET_URI` / `AUTH_JWT_ISSUER` / `AUTH_JWT_AUDIENCE` | `http://localhost:8081/.well-known/jwks.json` / `http://localhost:8081` / `fcourt` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `BRANCH_UPDATED_TOPIC` / `BRANCH_UPDATED_TOPIC_PARTITIONS` | `facility.branch-updated.v1` / `3` |
| `FACILITY_EVENTS_ENABLED` | `true` (outbox relay + topic creation + initial snapshots) |
| `FACILITY_OUTBOX_DELAY_MS` | `1000` |
| `FACILITY_BOOTSTRAP_SNAPSHOTS` | `true` |
| `SUBSCRIPTION_BASE_URL` | empty (use default limits below) |
| `SUBSCRIPTION_DEFAULT_MAX_BRANCHES` / `SUBSCRIPTION_DEFAULT_MAX_COURTS` | `3` / `15` |

### Schema

`src/main/resources/db/migration` holds `V1__init_schema.sql` and `V2__seed_sample_data.sql`
(JDBC versions of `database/V1`, `database/V2` without psql meta commands) and the additive
`V3__facility_management.sql`: `branches.district/latitude/longitude/bank_bin/event_version`,
`courts.default_price_per_hour`, unique live court name per branch, one live template per
court/day/start, checks (close > open, end > start) and the `facility_outbox` table.
V1 columns are unchanged, so `tools/demo_seed` keeps working.

A database created by the root `docker-compose.yml` already contains V1+V2 without Flyway
history: Flyway baselines it at version 2 and applies only V3+. An empty database runs V1..V3.

## Security

RS256 JWT through `AUTH_JWK_SET_URI`, exact issuer, audience `fcourt`, `exp` required, positive
numeric `sub`; role from `roles` array or `role` claim. Errors are RFC 7807 ProblemDetail
(`detail` is user-safe, validation errors add `errors: ["field: message"]`).

| Path | Access |
|---|---|
| `/api/admin/**` | ADMIN (court owner); the owner is always `sub`, other owners' rows answer 404 |
| `/api/staff/**` | STAFF (assignment is checked by staff-operations-service before it calls) |
| `GET /api/public/**` | anonymous (payment-service VietQR) |
| `GET /api/internal/**` | anonymous; for discovery-service only — restrict at the network/gateway |

## API

Times are `"HH:mm"` (requests also accept `"HH:mm:ss"`); money is a JSON number (VND).

**Branches (SCRUM-14)**

- `GET /api/admin/branches` → own, not deleted
- `POST /api/admin/branches` → 201; **403** when the subscription branch limit is reached
- `GET|PUT|DELETE /api/admin/branches/{id}` (DELETE = soft delete, 204)
- `GET /api/public/branches/{id}` → `{id,name,address,city,bankName,bankBin,bankAccountNumber,bankAccountName,status}`
- `GET /api/staff/branches/{id}` → `{id,status}`

Request: `name*, address*, ward, district, city*, latitude, longitude (pair), phone, openTime*,
closeTime* (> openTime, else 400), slotDurationMinutes (30|60, default 30), bankName*, bankBin*
(6 digits), bankAccountNumber* (6–20 digits), bankAccountName*, status (optional ACTIVE|INACTIVE)`.

**Courts (SCRUM-16)**

- `GET|POST /api/admin/branches/{branchId}/courts` (POST 201; **403** when the court limit is reached)
- `GET|PUT|DELETE /api/admin/courts/{id}`, `PATCH /api/admin/courts/{id}/status` `{status}`
- `GET /api/staff/courts/{id}` → `{id,branchId,status}`

Request: `name*, courtTypeId*, description, imageUrl, defaultPricePerHour* (> 0), status
(ACTIVE|MAINTENANCE|INACTIVE)`. A duplicate name in the same branch (case-insensitive, trimmed)
or a price ≤ 0 is **400**. Courts seeded by SQL have `defaultPricePerHour = null`.

**Time slot templates (SCRUM-18)** — day of week `0 = Sunday … 6 = Saturday`.

- `POST /api/admin/courts/{courtId}/time-slot-templates/preview` → `{courtId, slots:[{startTime,endTime,price}], totalSlots}`
- `POST /api/admin/courts/{courtId}/apply-template` → `{courtId, daysOfWeek, totalSlots, slots:[TemplateSlot]}`
- `GET /api/admin/courts/{courtId}/time-slot-templates?dayOfWeek=`
- `POST /api/admin/courts/{courtId}/time-slot-templates` `{dayOfWeek,startTime,endTime,price}` → 201 (exactly 30 minutes, inside branch hours, overlap → 409)
- `DELETE /api/admin/time-slot-templates/{id}` → 204

`ApplyTemplateRequest`: `startTime` (default branch openTime), `endTime` (default closeTime),
`durationMinutes` (only 30), `price`, `priceMultiplier` (default 1.0), `daysOfWeek` (default 0..6).
`SlotTemplateEngine` splits the range into back-to-back 30-minute blocks (06:00–06:30, 06:30–07:00, …).
End ≤ start, a range outside branch hours, or a range not divisible by 30 minutes is 400, so
no partial block is ever created. Applying replaces (soft-deletes) every live template of the
given days in the same transaction, so a day never mixes old and new layouts.
Slot price = `price ?? defaultPricePerHour × 30/60 × priceMultiplier` (2 decimals, HALF_UP).

**Discovery read API**: `GET /api/internal/courts/{courtId}/slot-schedule?date=YYYY-MM-DD`, shape of
`discovery-service/SCRUM-22_AVAILABLE_SLOTS.md`. `slotId` = template id (stable),
`outOfService` = court not ACTIVE or branch INACTIVE. 404 for a missing/deleted court, 200 with
`slots: []` when the court has no template for that weekday, `Cache-Control: no-store`.

## Branch events (`facility.branch-updated.v1`)

Implements the consumer contract in discovery-service's README ("Facility event contract").
`CourtCreatedEvent` / `SlotTemplateAppliedEvent` from the Jira texts are intentionally **not**
produced: nothing consumes them; this full snapshot plus the slot-schedule read API replace them.

- Every branch/court/status/template/delete change writes one full snapshot into `facility_outbox`
  in the same transaction. Key = branch id; value = `{branchId, version, name, address, city,
  district, latitude, longitude, active, deleted, courts:[{id, courtTypeId, minPrice, maxPrice}]}`.
- `version` comes from `branches.event_version`, incremented under the branch row lock: strictly
  increasing per branch, never a timestamp. A deleted branch publishes `deleted=true, active=false,
  courts=[]` (tombstone).
- `courts` = ACTIVE, non-deleted courts; min/max price over live templates (both null if none).
  No bank/owner data is published.
- `OutboxRelay` publishes pending rows in id order (acks=all, idempotent producer), marks a row only
  after the broker acknowledged it and stops at the first failure (at-least-once; consumers drop
  old versions). An advisory lock keeps one relay active across instances.
- Initial/recovery snapshot: on startup every branch whose `event_version` is 0 (rows inserted by
  SQL seeds) gets version 1. To force a full replay, set `event_version` back to 0 for the wanted
  branches and restart.
- The producer declares the topic (3 partitions) because discovery runs with `missing-topics-fatal`.

## Pending dependencies

- **subscription-service (SCRUM-10)** is not implemented. `SubscriptionLimitClient` uses
  `SUBSCRIPTION_DEFAULT_MAX_BRANCHES/COURTS` (3/15, the seed plan). With `SUBSCRIPTION_BASE_URL`
  set it calls the proposed `GET /api/internal/subscriptions/admins/{adminId}/limits` (Bearer
  forwarded) → `{maxBranches, maxCourts}`; 404 → 403 "No active subscription plan", other
  failures → 503. Wire it to the real contract when SCRUM-10 lands. Limits are enforced under a
  per-owner advisory lock, so concurrent creates cannot exceed them.
- **auth-service (SCRUM-6)**: only the JWT contract is used; the owner's account status is not rechecked.
- Court types (SCRUM-10/11) are not validated against subscription-service (`courtTypeId` > 0 only).
- Changing branch hours does not trim existing templates outside the new hours.

## Tests

`.\mvnw.cmd clean verify` (Java 21, Docker running): unit tests for the template engine and
request validation; `FacilityApiIntegrationTest` runs the full app against PostgreSQL 16
(Testcontainers) and embedded Kafka — owner scoping, 400/403/404/409 cases, plan limits,
template apply/replace, slot-schedule contract and the published snapshots/versions.
