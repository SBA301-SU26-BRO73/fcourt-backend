# SCRUM-91 — reassessment and local integration evidence

Date: 2026-11-05. Base: dev 4fa603b. Source: exported task assigned to
Phạm Đăng Khoa. Backend only; no Flutter repository changes.

The old harness used proposed registration/creation contracts. The owner
explicitly approved implementing the missing booking creation authority here.
See [ONLINE_BOOKING.md](../../booking-service/ONLINE_BOOKING.md) for API, atomicity,
recovery, migration and deployment configuration.

## Executed checks

| Scope | Result |
|---|---|
| Auth Java verify | 157 tests, zero failures/errors/skips |
| Booking Java verify | 50 tests, zero failures/errors/skips |
| Payment Java verify | 62 tests, zero failures/errors/skips |
| Staff Java verify | 125 tests and coverage gate passed |
| Hold Java verify | 22 tests, zero failures/errors/skips |
| Facility Java verify | 15 tests, zero failures/errors/skips |
| Reporting Java verify | 6 tests, zero failures/errors/skips |
| Python gateway runner | 14 HTTP fixture regression cases passed |
| Six-service Backend counter/POS smoke | 13 assertions passed with real PostgreSQL/Redis; Discovery/JWKS fixtures |
| Eight-service local gateway flow | **32 steps PASS**, real PostgreSQL/Redis/Kafka and real IAM-issued JWTs |

The local gateway flow runs actual Auth, Facility, Discovery, Hold, Booking,
Payment, Staff and Reporting apps. It registers an owner with multipart legal
document fields, approves through Super Admin, creates venue/templates/staff,
observes Kafka-driven discovery, creates bookings from holds, uploads a bill,
confirms payment and admits the ticket once. Rejected payment cancels its booking
and makes only its slot available. Reporting's API reflects one confirmed and
one cancelled booking with the correct revenue; its event inbox records exactly
one applied staff check-in event.

Additional database/HTTP tests cover concurrent online commands, online versus
walk-in, shifted template overlap, midnight/subsecond boundaries, lease refusal,
rollback, replay mismatch, forbidden account state/role, unknown client fields,
upstream failure and redirects, payment submission versus expiry, durable release
retry and one cancellation event.

## Reproduce

Build the eight service jars and test classes with Java 21/Maven. Then:

```powershell
mvn -f staff-operations-service/pom.xml dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=target/test-classpath.txt
python -m unittest discover -s tools/system_tests -v
python tools/system_tests/local_cluster.py --execute
```

The workflow runs this local cluster on pull requests. Default invocation without
`--execute` does not create resources. Reports go under ignored target/. The
helper owns and cleans only its disposable containers/processes. Child apps
inherit only operating-system settings plus explicit fixture configuration,
preventing accidental use of user-configured upstreams or signing keys.

Kafka's branch topic is created with three partitions before consumers start,
matching Facility. Creating one and expanding later caused a real fixture
startup race while consumers awaited metadata refresh; the topology is now
declared consistently rather than hiding it with a larger poll timeout.

## Boundaries of the evidence

- Document/receipt storage is replaced by test-classpath beans; no external
  Cloudinary or bank transfer occurs. Other business services and JWT signing
  are real. Local venue entitlement uses the existing facility sandbox defaults.
- A loopback reverse proxy forwards the actual gateway API routes; it does not
  establish deployed Nginx/TLS/CORS acceptance.
- Only the admission business clock is advanced after payments/Saga settle,
  using key-guarded test-only routes. JWT validation retains the system clock.
  Test launchers/clock routes are absent from production jars.
- Full-flow fixture success is not acceptance of every deployed service, bank,
  provider, production environment or device. productionAcceptance remains false.
- Request Review/Request Review Coding follows the supplied Jira text. No
  approval, review date or production evidence is fabricated.
