# Staff operations — SCRUM-26

Java 21 / Spring Boot 3.5, port **8090**, own PostgreSQL **staff_db**. Manages
STAFF assignments, IAM account commands and weekly work shifts for branch-owning
ADMINs. IAM/facility adapters use the proposed boundary in [CONTRACTS.md](CONTRACTS.md);
actual integration is pending those services. No cross-database access.

## Build and database verification

```powershell
mvn -f staff-operations-service/pom.xml clean verify
```

Docker must be running. Tests require a real PostgreSQL 16 Testcontainer; they
are not silently skipped when Docker is unavailable. Tests verify Flyway,
transactions, HTTP CRUD, ownership, RSA-signed JWT/JWKS, dependency failure,
idempotency concurrency and recovery after local rollback. JaCoCo writes
target/site/jacoco and enforces at least 80% line coverage in service logic.

## Local startup

Set STAFF_DATABASE_PASSWORD in the process environment. No password is committed
or supplied as an application default.

```powershell
docker compose -f staff-operations-service/compose.yml up -d
mvn -f staff-operations-service/pom.xml spring-boot:run
Invoke-RestMethod http://localhost:8090/actuator/health
```

Optional configuration: STAFF_DATABASE_URL (default
`jdbc:postgresql://localhost:5490/staff_db`), STAFF_DATABASE_USER (postgres),
STAFF_DATABASE_PORT (5490 for Compose), SERVER_PORT (8090).

The service-specific Compose database is separate from the repository-wide
bootstrap database. It is initialized by Flyway, without sample users or IAM.
Runtime migrations remove only psql meta-commands from the inherited schema.
Automatic baselining is disabled: an existing initialized database must be
compared and explicitly baselined before using this service. Changing Compose's
port also requires updating STAFF_DATABASE_URL.

## Identity configuration

AUTH_URL (http://localhost:8081), AUTH_JWK_SET_URI
(http://localhost:8081/.well-known/jwks.json), AUTH_JWT_ISSUER
(http://localhost:8081), AUTH_JWT_AUDIENCE (fcourt), FACILITY_URL
(http://localhost:8083). Configure trusted private upstreams and TLS in deployed
environments. Connect/read timeouts are 2/3 seconds; local write transactions
have a 15-second timeout. Tokens/passwords are never logged or persisted.

JWT requires RS256 signature, issuer, audience, mandatory exp and positive
numeric sub. Roles come from roles array or scalar role. Every operation also
checks live IAM activity, so a disabled account cannot use a still-valid JWT.
ADMIN operations verify facility branch ownership; STAFF operations derive the
branch from the active local assignment and validate facility branch activity.
Malformed/unavailable dependencies fail closed. Health exposes only status.

## API

All business responses have Cache-Control: no-store. Send Bearer JWT; writes
also require Idempotency-Key containing a UUID.

| Method / route | Role | Behavior |
|---|---|---|
| GET /api/admin/staff?branchId=7&limit=50&offset=0 | ADMIN | Owned branch staff including inactive, ordered by ID; max limit 100 |
| POST /api/admin/staff | ADMIN | Provision account, persist fixed branch assignment/shifts; 201 |
| PUT /api/admin/staff/{id} | ADMIN | Update profile and replace shifts; immutable branch/email |
| DELETE /api/admin/staff/{id} | ADMIN | Disable IAM account and soft delete assignment; return inactive staff |
| GET /api/staff/me | STAFF | Resolve own active assignment and branch |

Create body:

```json
{
  "branchId": 7,
  "email": "staff@example.com",
  "password": "replace-with-a-strong-password",
  "fullName": "Staff member",
  "phone": "0912345678",
  "shifts": [{"dayOfWeek": 1, "startTime": "09:00", "endTime": "17:00"}]
}
```

Update accepts only fullName, phone, shifts. Unknown fields are rejected.
ISO dayOfWeek: 1 Monday–7 Sunday. Branch-local, minute-precision work shifts
must end within the same day; they may abut but not overlap, max 28/week. Empty
shifts clears assignments. These are employee work metadata, not booking slots.
Password is 12–72 characters, max 72 UTF-8 bytes, forwarded only to IAM.

Response: id, userId, branchId, email, fullName, phone, active, createdAt,
updatedAt, shifts. Profile fields are IAM snapshots, nullable for legacy rows
until updated. Live account activity is always resolved separately from IAM.

Errors: 400 validation/header, 401 JWT, 403 role/ownership/activity, 404 missing
resource, 409 assignment/inactive update/key conflict, 503 upstream unavailable
or invalid. Validation never echoes credentials or raw upstream response bodies.

## Idempotency and partial failures

Writes serialize actor+UUID with a PostgreSQL advisory transaction lock. Local
assignment, shifts, command record and audit commit atomically. IAM receives
the stable key staff:<actorId>:<UUID> and must guarantee durable replay.
Replaying a successful command returns current assignment state; mismatched
metadata with the same key returns 409. Create replays also return 201.

Create fingerprints intentionally exclude passwords. The first IAM credential
wins on retry; this is never a password-reset interface. No password hash or
plaintext is saved to staff_db. Use IAM's separate password-change workflow.

IAM success can precede local rollback. Retry the same key/request to recover;
never silently use a new key on ambiguous timeout. IAM must reject conflicting
metadata even if the local transaction never committed. Unresolved operations
require reconciliation using the scoped key and caller-held original request;
there is no automatic background replay storing passwords or distributed
transaction. Live IAM checks fail closed after remote disablement even if local
commit needs retry. Soft-deleted users cannot be silently reassigned/reactivated.

Flyway V2 adds profile snapshots, shifts, commands, audit and branch index.
Existing legacy DBs require an explicit reviewed V1 baseline before V2.

See REQUEST_REVIEW.md and VALIDATION.md for scope and actual verification.
No live auth/facility/Flutter acceptance is claimed by stub-based tests.

## Schedule and QR check-in — SCRUM-28

- GET /api/staff/schedule/today resolves active staff assignment and queries the
  bounded booking schedule in STAFF_TIME_ZONE (Asia/Ho_Chi_Minh by default).
- POST /api/staff/check-in/verify accepts `{"checkInCode":"<raw QR code>"}`
  with STAFF Bearer and Idempotency-Key UUID; returns bookingId, branchId, date,
  CHECKED_IN status and checkedInAt. Code length 1–128, treated as opaque text.
- Atomic state/date/branch/code verification belongs to the booking command
  authority. Repeated code with a new key returns 409; same key replays success.
- Command result and BookingCheckedInEvent outbox commit atomically locally;
  retry same key after ambiguous remote/local failure. Raw codes are not stored.
- Configure BOOKING_URL, KAFKA_BOOTSTRAP_SERVERS, STAFF_EVENTS_TOPIC and
  STAFF_EVENTS_ENABLED. Supply a running Kafka broker for relay delivery.

See CONTRACTS.md for the proposed booking API, consumer groups, at-least-once
deduplication and integration limits. Tests include actual Kafka/PostgreSQL
outbox acknowledgement, retained failure and same-ID duplicate after DB rollback.

## Walk-in / checkout — SCRUM-30

POST /api/staff/walk-in accepts courtId, date (today), slotIds (1–48 contiguous
slots), guestPhone. Requires STAFF
Bearer + UUID Idempotency-Key. Branch/actor/status/price/payment method are
server-derived. Returns 201 with authoritative CASH CHECKED_IN booking result.

PUT /api/staff/bookings/{id}/checkout uses the same security/key headers and no
body; returns COMPLETED with completedAt. Booking authority performs the atomic
state and occupancy transition. Same-key replay is safe; new-key wrong state
returns 409. Local success results and events commit atomically in staff_db.

DISCOVERY_URL defaults http://localhost:8085 and uses SCRUM-22's public slot
contract. Facility court ownership and availability are checked for new walk-ins;
the authoritative booking command still must guard against races. Remote-success
reconciliation runs before availability so an already-created booking can recover
from a local transaction failure. See CONTRACTS.md for required cash/booking and
read-only reconcile endpoints; live integration is not claimed.

## Safe multi-slot CASH invoices — SCRUM-61

The selection must contain distinct canonical contiguous slots for the one court
and date; input ordering does not affect price-line ordering. Availability,
gRPC HoldSafety.Check and BookingSafety.Check are preflight reads. Final booking
creation MUST atomically coordinate the shared Redis hold/durable booking
protocol, including online writers. A successful pair of reads never reserves
the slots by itself.

Protobuf contract: src/main/proto/slot_safety.proto. Configure HOLD_GRPC_TARGET
(localhost:9087), BOOKING_GRPC_TARGET (localhost:9088), STAFF_GRPC_DEADLINE_MS
(2000). Both RPCs forward the signed STAFF Bearer metadata and have deadlines;
partial/malformed/unavailable replies fail closed. Channels use TLS by default;
STAFF_GRPC_PLAINTEXT=true is available for local fixtures. New walk-in transactions
are bounded to 45 seconds for the complete dependency chain; do not configure
timeouts/retry behavior independently of the upstream idempotency protocol.

Owner-approved server pricing: fixed + base × percentage/100, round surcharge
once to two decimals HALF_UP. WALK_IN_FIXED_SURCHARGE and WALK_IN_SURCHARGE_PERCENT
both default 0; percentage range 0–100. WALK_IN_PRICING_POLICY defaults counter-v1.
The canonical quote (base/surcharge/total/policy/price lines) is sent to booking
authority, which must independently validate and persist it before accepting CASH.
Amounts must fit numeric(10,2). Client price/fee overrides are rejected.

Response adds invoice with receiptCode, cashierUserId, baseAmount,
surchargeAmount, totalAmount, policyVersion and sorted item price/time snapshots.
The JSON is printable invoice data; Flutter handles presentation in its repo.
Retries reconcile the original immutable quote/invoice even if current pricing
has changed; never recalculate already committed CASH receipts. This is an
additive response change, not a new local payment database.

## Weekly schedule — SCRUM-63

GET /api/staff/schedule?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD requires active
STAFF. Range is inclusive, 1–7 days; branch is derived from assignment, never
from a client filter. Returns branchId/startDate/endDate/generatedAt and courts
containing chronological days and sorted slots, including empty days/courts.
Each slot has booking/customer fields and flags: lateCheckIn, playing, endingSoon.

CONFIRMED is late strictly after start+15 minutes. CHECKED_IN means playing
until checkout even if the scheduled end passed; endingSoon is only the final
configured window before scheduled end. STAFF_ENDING_SOON_MINUTES defaults 10
(owner-approved), range 1–60. All flags in one response share generatedAt and
STAFF_TIME_ZONE; final midnight slots end on the following day. Completed,
cancelled and pending-payment records do not get playing/late flags.

One bulk booking read per request; no per-slot I/O or schedule cache. State is
the authority snapshot at query time. Test benchmark report is generated at
target/schedule-benchmark.json; local measurements and limitations are in
VALIDATION_SCRUM_63.md.
