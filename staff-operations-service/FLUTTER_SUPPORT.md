# Flutter API support

## Delivered contracts

ADMIN reactivation requires owned branch, stable UUID and validated IAM ACTIVE
response before restoring the local assignment. GET managed-account lookup lets
IAM independently verify ownership. Auth authority is delivered in a separate
support PR; role and active assignment checks remain server-owned.

STAFF preview is read-only. Booking support resolves exact QR or printed manual
code and guards admission with a durable command ledger and atomic row update.
The signed offline roster includes SHA-256 code hashes, actor, branch, booking,
date and admission window; no raw QR appears in the Flutter snapshot.

Offline replay validates the signed permit, original occurrence time, current
staff assignment and 24-hour reconciliation limit. Stable commands produce one
booking transition and one staff audit/outbox record. New-key duplicates conflict.
Booking requires the dedicated X-Staff-Service-Token as well as the STAFF JWT;
this prevents direct callers from bypassing permit verification in staff service.

## Server configuration

STAFF_OFFLINE_SIGNING_KEY and STAFF_BOOKING_SERVICE_TOKEN require at least 32
bytes. The service token must match booking configuration. Keep all values only
in server secret storage. STAFF_OFFLINE_PREVIOUS_SIGNING_KEYS accepts up to three
comma-separated previous signing keys for pending permits during rotation.
Deploy Flyway migrations and the corresponding auth/booking support branches.

## Evidence and limits

2026-11-01: staff Maven verify passes 121 tests with real PostgreSQL/Kafka and
JaCoCo service coverage gate. Auth passes 22 tests; booking passes 11 tests.
Run `python tools/flutter_support_e2e.py` on Windows with Docker, Java 21 and
built service jars. Its disposable three-database smoke verifies live service
reactivation, preview, signed roster, replay, tamper rejection and manual/QR
mutual exclusion. Facility and JWKS are local fixtures; this is not deployed
acceptance. The harness cleans its own processes and container.

Walk-in guestName is fingerprinted and forwarded; the owning creation authority
must persist it. Checkout preview is a guarded projection adapter; authoritative
fee computation and actual settlement remain upstream dependencies. Existing IAM
create/edit/disable contracts and real gateway routing also require integration
acceptance. Android camera/background/reconnect timing remains device work.

## Review follow-up — 2026-11-01

Owning APIs now delivered: booking #30, IAM #31, hold #41 and facility #42.
Staff create/edit/disable/reactivate and live day/week boards pass six-service
smoke, as do QR/manual/offline admission, walk-in contact persistence, public hold
conflict, overtime and attached POS settlement. Backend counts: staff 121,
booking 29, IAM 25, hold 14, facility 8, POS 42. Discovery/JWKS remain fixtures.

Checkout requires expectedSurcharge and expectedTotal from the staff preview.
Overtime follows the owner-approved rule: each started 30 minutes at the
corresponding facility slot price. A changed quote returns 409 for reconfirmation.
Booking stores POS charges and durable settlement outbox; retry never recollects
cash. Walk-in/reconcile/checkout use the dedicated server credential.

Run the smoke with explicit --staff-jar, --auth-jar, --booking-jar, --facility-jar,
--hold-jar, --pos-jar and --output paths after building each service. It creates
and removes its own disposable PostgreSQL/Redis containers and Java processes.
Configure BOOKING_HOLD_SERVICE_TOKEN on hold/booking, POS_BOOKING_SERVICE_TOKEN
on POS/booking and POS_SETTLEMENT_SERVICE_TOKEN on POS/booking. Enable
counter.grpc.enabled on hold/booking; default host 127.0.0.1 ports 9087/9088.
Non-loopback gRPC requires TLS certificate/private key; no secret in Flutter.
The earlier authority-pending paragraphs are historical, superseded here.
