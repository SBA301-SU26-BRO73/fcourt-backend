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
