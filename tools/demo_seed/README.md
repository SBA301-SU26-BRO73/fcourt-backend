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
The staff schema must include Flyway V2 (profile columns) from SCRUM-26. Fixture
tests apply the inherited V1 schemas plus this migration before seeding; profile
email/name/phone match the corresponding IAM fixture accounts.

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

## Verification

```powershell
python -m unittest discover -s tools/demo_seed -p test_seed.py -v
```

**12 tests pass, no skips**, including original service SQL schemas and seeds on
a real PostgreSQL 16 container with no external port/user data volume. Verifies
rerun, partial failure/resume, ownership collisions, changed-manifest/data refusal,
sequence advancement, quote/dollar-value roundtrip, CLI dry-run and credential
redaction. Unit checks also validate references, totals and non-overlapping slots.
Docker absence fails tests rather than silently skipping database verification.

**Actual venue/bank/Cloudinary acceptance is NOT RUN:** the owner has not supplied
those inputs. Fixtures are not presented as real venue data or end-to-end proof.
