# SCRUM-91: gateway flow and Saga

## Plan and scope

Exercise a disposable owner's registration/approval, branch/court/slot setup,
staff provisioning, discovery projection, customer hold/booking, receipt upload,
owner confirmation, staff check-in and duplicate rejection. A second booking is
rejected: assert CANCELLED and eventual slot release while the first slot stays
BOOKED. Writes run once with unique idempotency keys; only reads are polled.
The runner operates through a single gateway, never through another service's DB.

`flow.py` implements assertions/orchestration. `gateway.py` pins the origin,
validates TLS, refuses redirects, bounds responses and uploads a supplied image.
`test_flow.py` uses a real loopback HTTP server to test the runner, including
broken compensation, HTTP failures, credential scope and write preflight.

## What is verified here

Local fixture execution verifies **the runner**. It does not prove Kafka, Redis,
real IAM/facility/discovery/hold/booking/payment integration, actual receipt
storage or QR camera support. Live acceptance is **NOT_RUN**. See
[LIVE_VALIDATION.md](LIVE_VALIDATION.md).

## Before live execution

1. Deploy all upstream services, staff service and gateway to an isolated
   local/test/staging environment. Production writes are rejected.
2. Confirm deployed gateway mappings match this repository's current contracts.
   Owner registration uses multipart /api/auth/register/court-owner; creation
   uses the new POST /api/bookings authority. See REASSESSMENT.md.
3. Supply a super-admin and existing customer, a controlled email domain and
   strong passwords for new disposable owner/staff accounts. Owner approval must
   activate a trial/entitlement permitting branch/court creation, or the gateway
   must offer a sandbox policy. Without this, fail and coordinate entitlement;
   do not bypass subscription authorization or write its DB.
4. Supply an authorized owner test document via legalDocumentFile (PNG/JPEG/PDF),
   authorized bank/venue/court data, court type ID and an explicit PNG/JPEG
   test receipt <=5 MiB. Use a test receipt provider/storage account. The script
   uploads evidence; it never makes a bank transfer. No real receipt is bundled.
5. Configure date/time policy consistently: Asia/Ho_Chi_Minh, future slots today,
   public facility templates Sunday=0. Staff shifts use ISO Monday=1 separately.
   Run in the 15 minutes before the next half-hour, early enough for two adjacent
   future slots within venue hours. The live CLI enforces the real admission
   window; the disposable local-cluster helper uses test-only clock controls.
6. Confirm final DTOs before claiming live acceptance. Required examples:
   login `{accessToken}`, created resources `{id}`, search `{content:[{id,
   courtCount}]}`, slots array `{slotId,startTime,status}`, hold `{holdToken,
   slotIds}`, booking `{status,branchId,date}`, checkout `{bankAccountNumber,
   amount}`, receipt `{id}` (payment ID), ticket `{checkInCode}`, check-in
   `{status,branchId}`. Booking states and slot states are asserted explicitly.

## Run

Python 3.12+, standard library only. From repo root:

```powershell
python -m unittest discover -s tools/system_tests -v
Copy-Item tools/system_tests/config.example.json tools/system_tests/staging.local.json
# Edit every REPLACE_ value and confirm routes/contracts first.
# Set FCOURT_E2E_SUPERADMIN_EMAIL, FCOURT_E2E_SUPERADMIN_PASSWORD,
# FCOURT_E2E_CUSTOMER_EMAIL, FCOURT_E2E_CUSTOMER_PASSWORD,
# FCOURT_E2E_CUSTOMER_PHONE, FCOURT_E2E_OWNER_PASSWORD,
# FCOURT_E2E_STAFF_PASSWORD securely in this process environment.
python tools/system_tests/flow.py --config tools/system_tests/staging.local.json
# Only after reviewing the disposable-data write scope:
python tools/system_tests/flow.py --config tools/system_tests/staging.local.json --execute --allow-test-writes
```

Default execution makes no HTTP calls and returns NOT_RUN. Exit 0 means all flow
assertions passed in the declared scope; exit 2 means NOT_RUN/BLOCKED/FAIL.
Reports are JSON plus Markdown under ignored `target/`. They omit credentials,
responses, QR codes, receipt bytes and bank data. Fixture reports always set
`liveAcceptance=false`. A live PASS covers these assertions only, not all product
acceptance criteria. Never rename a fixture report into a live report.

Connection timeout is 5 seconds per request; poll settings bound repeated reads
(one in-flight read may finish after the polling deadline). No write retries:
failure can leave partial resources. Report IDs support manual inspection and
authorized cleanup through service APIs. The runner never deletes data. Each
rerun uses a new run ID; keep the failed run report when investigating recovery.

## Review / external gates

One Backend PR targeting dev. Commit boundaries: occupancy/hold authority,
online creation, expiry/recovery, client contracts, regression tests, local
cluster fixtures, configuration/runbook and CI.
Review against agreed gateway contracts, then execute with real test services,
Kafka/Redis, test image storage and authorized accounts. Flutter/device steps
belong in the separate Flutter repo. No Jira state changes are automated.

## Current local cluster

The SCRUM-91 follow-up targets dev directly and implements the missing booking
creation authority with its owning service boundaries. Run
`python tools/system_tests/local_cluster.py --execute` after building the eight
service jars and staff test dependency classpath. CI runs the same scenario.
See [REASSESSMENT.md](REASSESSMENT.md) for 32-step evidence, exact fixture scope,
configuration isolation and cleanup. This does not establish production readiness.
