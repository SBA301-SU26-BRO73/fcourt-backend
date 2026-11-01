# SCRUM-22 — Available slots by court and date

## Public API

`GET /api/public/courts/{courtId}/slots?date=YYYY-MM-DD` requires no JWT.
The response is a JSON array, compatible with SCRUM-24's DiscoveryGateway:

```json
[{"slotId":37,"startTime":"18:00:00","endTime":"18:30:00","price":90000,"status":"AVAILABLE"}]
```

Returns every configured 30-minute slot for the requested date, sorted by start
time, including unavailable and past slots. An existing court with no configured
slots returns `[]`. Closed hours outside the schedule are not invented. A final
23:30–00:00 slot ends at midnight of the next day. Prices use BigDecimal without
conversion or rounding. Slot IDs must be stable across facility, booking and Redis.

Status precedence: `EXPIRED > OUT_OF_SERVICE > BOOKED > HOLD > AVAILABLE`.
EXPIRED means the slot's start is less than or equal to the current time in
`Asia/Ho_Chi_Minh`, including past-day booked/held slots. Whole-court closure
or per-slot maintenance/private events produce OUT_OF_SERVICE. Closure takes
precedence over a conflicting booking; this precedence is recorded for review.

Responses use `Cache-Control: no-store`. Each request reloads schedule, occupancy
and Redis; no branch-search cache is reused. Redis keys that expire disappear
from the next read without a cleanup job. No hold token/contact data is exposed.

Errors: 400 invalid/nonpositive court ID or missing/invalid date; 404 when facility
reports court missing; 503 unavailable/invalid dependency. Malformed templates,
overlaps, duplicate IDs, mismatched court/date and inconsistent occupancy fail
closed instead of manufacturing AVAILABLE slots. HTTP connect/read timeouts are
1s/2s; Redis connect/command timeouts are 1s. There are no dependency retries.

This is a point-in-time availability display, not a reservation guarantee.
Concurrent bookings/holds can change immediately after reads. Booking writers
must coordinate durable booking and the same Redis lock protocol as SCRUM-24.

## Proposed upstream HTTP contracts

The user authorized contracts and sample data for upstream APIs absent from dev.
These contracts are exercised by fixtures; actual facility/booking implementations
remain owned by their services. Discovery never reads another service's database.
No production write/admin endpoint is added to discovery.

Facility: `GET /api/internal/courts/{courtId}/slot-schedule?date=YYYY-MM-DD`

```json
{
  "courtId":1,"date":"2030-01-01","outOfService":false,
  "slots":[
    {"slotId":37,"startTime":"18:00:00","endTime":"18:30:00","price":90000,"outOfService":false},
    {"slotId":38,"startTime":"18:30:00","endTime":"19:00:00","price":90000,"outOfService":false},
    {"slotId":39,"startTime":"19:00:00","endTime":"19:30:00","price":90000,"outOfService":true}
  ]
}
```

All fields are required. A maximum of 48 nonoverlapping 30-minute slots is accepted;
times must have minute precision. Price must be nonnegative, at most 2 decimal
places. Facility resolves weekday/calendar applicability, final price, active
templates and maintenance/private-event intervals. It returns 404 for a nonexistent
court and 200 with an empty slots array for an existing court without templates.
The proposed read API is an alternative integration boundary while SCRUM-18's
SlotTemplateAppliedEvent schema/producer is absent; this task adds no Kafka slot
projection. Upstream owners still need to implement/adopt the read contracts.

Booking: `GET /api/internal/courts/{courtId}/slot-occupancy?date=YYYY-MM-DD`

```json
{"courtId":1,"date":"2030-01-01","bookedSlotIds":[37]}
```

All fields are required; empty means there are no durable bookings. Include only
currently effective bookings occupying the supplied facility slot IDs. Cancelled
or released bookings must be omitted. IDs must be unique and present in that
day's schedule; inconsistent snapshots return 503 for reconciliation. Booking
404 is a dependency error, not evidence of an empty court. Both internal endpoints
must be restricted to trusted service networking when implemented.

Redis follows the existing SCRUM-24 branch protocol:
`fcourt:hold:{courtId}:{date}:{slotId}` stores a string hold token with TTL 300s.
Discovery performs one MGET for the configured slots, interpreting each existing
key as HOLD. It neither writes nor extends a hold. Default address matches the
hold engine's host port 6387. Both services must use the same Redis instance/DB.

## Local sample APIs and Postman

From `discovery-service`, run the fixtures in a separate terminal:

```powershell
python examples/slot_sources.py
```

This local-only server listens on `127.0.0.1:8096`, recognizes court 1, generates
48 slots, marks ID 37 BOOKED and ID 39 OUT_OF_SERVICE. Other courts return 404.
It is sample data for any requested date, not a persistent booking service.

Start PostgreSQL, Kafka and Redis with `docker compose up -d postgres kafka topics redis`.
If SCRUM-24's Redis is already bound to 6387, reuse it and omit `redis` from that
command; do not start two Redis instances on the same host port. Then:

```powershell
$env:DISCOVERY_DB_URL = 'jdbc:postgresql://localhost:5545/discovery_db'
$env:DISCOVERY_DB_USER = 'discovery'
$env:DISCOVERY_DB_PASSWORD = 'discovery_local'
$env:FACILITY_URL = 'http://127.0.0.1:8096'
$env:BOOKING_URL = 'http://127.0.0.1:8096'
.\mvnw.cmd spring-boot:run
```

Set a sample hold from another terminal (replace the container target if reusing
the hold engine's Redis):

```powershell
docker compose exec redis redis-cli SET fcourt:hold:1:2030-01-01:38 sample-token EX 300
curl.exe 'http://localhost:8085/api/public/courts/1/slots?date=2030-01-01'
```

Import `examples/slots.postman_collection.json`. A future date shows ID 37 BOOKED,
38 HOLD when seeded, 39 OUT_OF_SERVICE and other slots AVAILABLE. Use a past date
to observe EXPIRED. The collection uses sampleDate 2030-01-01 by default; change
it when testing after that date. In the Compose app container, use host-reachable
upstream URLs; 127.0.0.1 refers to the container itself. The local fixture binds
loopback for host execution; full Compose production upstreams are separate.

Configuration: FACILITY_URL (http://localhost:8083), BOOKING_URL
(http://localhost:8088), REDIS_HOST (localhost), REDIS_PORT (6387), REDIS_PASSWORD
(empty for local development). Never point production to sample APIs.

## Validation and review

Run `.\mvnw.cmd verify` with Java 21 and Docker running. Testcontainers runs real
Redis 7; embedded PostgreSQL and Kafka exercise the existing discovery behavior.
The HTTP integration test starts the full Spring Boot server on a random port,
uses actual fixture HTTP calls, seeds a Redis hold, verifies every status and
checks that Redis expiration is reflected on the next HTTP request. Separate
tests cover 48 slots, midnight, status precedence, missing courts, invalid dates,
duplicate/overlapping templates, upstream failures and the exact SCRUM-24 shape.
No end-to-end facility/booking integration or production race guarantee is claimed.

Local evidence (2026-10-27, Microsoft OpenJDK 21.0.12, Docker Desktop):
`mvnw.cmd -q clean verify` completed successfully: 30 tests discovered, 29 passed,
0 failures/errors, 1 skipped. The skipped test is the pre-existing opt-in branch
performance benchmark (`discovery.benchmark`); this task adds no performance SLA.
All 16 new slot tests ran, including real HTTP and Redis 7 expiration checks.
Compose configuration validation, Python fixture syntax, sample HTTP API smoke
checks and Postman collection JSON parsing also passed. The Postman GUI itself
was not run; API behavior was verified by automated real HTTP requests.

Task: https://bro73.atlassian.net/browse/SCRUM-22

Approach record: https://bro73.atlassian.net/browse/SCRUM-130 (Từ Phúc Nguyên).
Code review: https://bro73.atlassian.net/browse/SCRUM-138 (Từ Phúc Nguyên).
PR: https://github.com/SBA301-SU26-BRO73/fcourt-backend/pull/6, requested reviewer
`donan2k5`. CI runs on feature-branch pushes; live results are on the PR Checks
tab. Local verification is recorded above independently of CI.
The user confirmed prior approval; this traceability record was created during
implementation without backdating approval. The project lacks custom review work
types, so review records use Task plus [Review]/[Code Review] summary prefixes.

Branch: `feature/Dang-discovery-available-slots`, based on
`feature/Dang-discovery-service` at `8446c44786bf6183151baf36fbe139dad638ea28`.
The preceding discovery task is PR #4 and is not merged. The new PR targets that
feature branch so only SCRUM-22 changes appear. No code is merged by this task.
After the responsible person merges PR #4, move only SCRUM-22 commits onto dev
and retarget the PR, then rerun checks; do not merge this PR into the parent
feature branch. With squash merging, a plain base change may retain old changes.
