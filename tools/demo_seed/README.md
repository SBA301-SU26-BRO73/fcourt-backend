# SCRUM-92 — Resumable demo seeder

Administrative fixture/data tooling; no application endpoint or cross-service DB
access is added to runtime services. Python 3.12 standard library + Docker/psql.

## Dataset

Seven service databases: auth, subscription, facility, staff, voucher, payment,
booking. In addition to the four explicitly named in Jira, staff/entitlement and
payment records make the referenced demo identities/booking states consistent.

- 1 ADMIN, 3 STAFF, 5 CUSTOMER accounts with supplied bcrypt password hash.
- One subscription to an existing active plan supporting >=10 courts/3 branches.
- 3 branches, 10 courts (4/3/3), 2240 uniform 30-minute weekly templates (06–22).
- 3 active staff assignments and 2 branch-scoped vouchers used once each.
- 30 bookings, 5 each CONFIRMED/CHECKED_IN/COMPLETED/AWAITING_CONFIRMATION/
  PENDING_PAYMENT/CANCELLED, with matching payment states and discount sums.
- 50 actual booking_slots: cancelled bookings own no slots. Completed examples
  are on demoDate-1; others on demoDate. Check-in/payment times match state.

Every record is clearly named DEMO. Seed dates are relative to explicit demoDate,
not silently advanced on rerun. All weekdays get identical templates so the
fixture does not depend on an unresolved weekday-number convention.

## Configure, preview, apply

Copy manifest.example.json to manifest.local.json (ignored). Replace venue
addresses, numeric bank accounts/QR images, 10 authorized Cloudinary court images,
receipt asset and demoDate. Set demoOnly=false for your real-asset demo. The
example intentionally contains placeholders and is not real/verified venue data.
Each branch also requires `bankBin`, the bank's six-digit identifier as a string.
Leading zeros are preserved. Example `000000` is a format-only fixture, not a
verified bank code. A QR image URL cannot replace bankBin for payment checkout.

```powershell
python tools/demo_seed/seed.py --manifest tools/demo_seed/manifest.local.json
# Set FCOURT_DEMO_PASSWORD_HASH to bcrypt cost >=10 in the process environment.
# The matching demo login password is kept outside this repository.
python tools/demo_seed/seed.py --manifest tools/demo_seed/manifest.local.json --container fcourt_postgres --apply
```

Default is dry-run: validates/counts only, never connects or writes. --apply
requires an explicit container. --user defaults postgres. Run after service
schemas/catalogs have been initialized. Plan/court type IDs are validated against
subscription_db; no silent replacement of another owner's catalog is attempted.
The staff schema must include Flyway V2 (profile columns) from SCRUM-26. Facility
must include its owning service's V3 migration (`bank_bin`, facility PR #20).
The seeder checks for bank_bin before writing any seed rows and never adds or
baselines another service's schema itself. Fixture tests apply the inherited V1
schemas plus staff V2 and facility V3 before seeding; profile
email/name/phone match the corresponding IAM fixture accounts.

Tests vendor the exact facility V3 SQL from commit
`5fc2ffe83226b90dd23e2cf31782534ec9099545` in `fixtures/facility_v3.sql`.
This is a test-only compatibility snapshot, not a runtime migration. Update the
fixture against the owning service when its contract changes.

For explicitly fake local fixture data only:

```powershell
python tools/demo_seed/seed.py --manifest tools/demo_seed/manifest.example.json --allow-fixture-assets
```

Applying fixtures also requires --allow-fixture-assets, --apply, --container and
the bcrypt environment variable. Bank-account strings preserve leading zeros.
The tool never contacts Cloudinary, generates bank transfers or proves photo
quality/real ownership; output always says assetsVerified=false. Live image/venue
acceptance must be checked with the supplied real data in the application.

## Non-overwrite and recovery protocol

idBase reserves a free aligned 10000-ID block per seeded table. Existing rows in
that block without matching seed ownership abort the step; no rows are deleted
or overwritten. Each database gets fcourt_demo_seed_manifests with seedKey,
manifest fingerprint, ID base and demo date. Same manifest/hash/data is idempotent;
changed manifest or edited seed row fails instead of resetting user actions.
Use a new key and free block for a new dataset. SQL values are escaped, target
databases/table identifiers allowlisted, timestamps normalized and generated
values compared to actual persisted rows before each commit.

Each service step is its own transaction, serialized by an advisory lock, with
lock/statement deadlines. There is no distributed transaction. A failure can
leave earlier service steps committed: fix the dependency and rerun the exact
same manifest/password hash to finish, without duplicate accounts/bookings.
Overall success is printed only after all steps commit. Sequence values move
past the reserved block without rewinding existing counters; gaps are normal.

Do not use this as a production migration, account password-reset tool or demo
reset. It does not automatically baseline Flyway, delete real records or undo
completed demo actions. Run on a designated demo/test database container.
Runtime services pointed at legacy bootstrap DBs still need their documented
reviewed Flyway baseline/migrations.

Percentage discounts are capped and rounded once to 0.01 using ROUND_HALF_UP
before subtracting from the two-slot total. Payment amount + discount equals the
original booking total exactly, including prices such as 10000.01 or 10000.03.
Stored-row verification uses these rounded values, not fractions PostgreSQL
would silently round during insertion.

Manifests made before the bankBin requirement must be updated. Use a new seedKey
and free idBase for an existing older dataset: changed manifest fingerprints
remain rejected; the tool does not rewrite old seed rows or banking details.

## Verification

```powershell
python -m unittest discover -s tools/demo_seed -p test_seed.py -v
```

**17 tests pass, no skips**, including original service SQL schemas and seeds on
a real PostgreSQL 16 container with no external port/user data volume. Verifies
rerun, partial failure/resume, ownership collisions, changed-manifest/data refusal,
sequence advancement, quote/dollar-value roundtrip, CLI dry-run and credential
redaction. Unit checks also validate references, totals and non-overlapping slots.
Regression checks cover fractional discounts rounded down/up and at the cap,
actual apply/rerun against numeric(10,2), bankBin format/leading-zero persistence
with facility V3, and refusal of a missing bank schema before any seed writes.
Docker absence fails tests rather than silently skipping database verification.

**Actual venue/bank/Cloudinary acceptance is NOT RUN:** the owner has not supplied
those inputs. Fixtures are not presented as real venue data or end-to-end proof.
