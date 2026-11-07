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
- 3 active staff assignments and 2 unused branch-scoped vouchers.
- 31 bookings: 5 each CONFIRMED/CHECKED_IN/COMPLETED/AWAITING_CONFIRMATION/
  PENDING_PAYMENT/CANCELLED, with matching payment states and discount sums.
- One additional CONFIRMED all-day booking on demoDate+1, court 1, all 32 slots
  from 06:00 to 22:00. The owner confirmed this meaning of "Bao sân"; no new
  booking status or maintenance feature is invented.
- 82 actual booking_slots: cancelled bookings own no slots. Completed examples
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
must include its owning service's V3 migration (`bank_bin`, hourly base price).
The seeder checks for bank_bin before writing any seed rows and never adds or
baselines another service's schema itself. Payment requires V2/V3/V4 for checkout,
ticket/expiry and customer ownership. Fixture tests apply all current migrations
from the owning services in numeric version order before seeding; profile
email/name/phone match the corresponding IAM fixture accounts.

The old `fixtures/facility_v3.sql` snapshot is historical; tests now use the
owning service's current migrations, including constraints added after V3.

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

The owner approved leaving vouchers unused because the current checkout API
does not apply voucher discounts. Slot sum, booking total, checkout snapshot and
payment amount are therefore equal; discount is zero. Fractional prices such as
10000.01 remain exact to cents, including the 32-slot invoice. The manifest price
must fit numeric(10,2) for a full day. Seeded phone strings follow the ten-digit
Vietnamese format, and ticket codes are matching 32-digit strings in both DBs.

Each payment now has a typed checkout snapshot, expiry, customer ownership and
ticket/QR metadata. Court hourly base prices are populated. Fixture bank accounts
are numeric format examples, not verified accounts or evidence of real transfers.

Generation version `runtime-v2` is part of the immutable fingerprint. Use a new
seedKey and free idBase for an old dataset; the seeder never rewrites previous
demo/user actions. Phone suffixes derive from IDs modulo 100000000; a collision
with existing data fails through the owning schema's uniqueness rules.

Manifests made before the bankBin requirement must be updated. Use a new seedKey
and free idBase for an existing older dataset: changed manifest fingerprints
remain rejected; the tool does not rewrite old seed rows or banking details.

## Verification

```powershell
python -m unittest discover -s tools/demo_seed -p test_seed.py -v
```

The current reassessment runs the complete seed suite against current service
migrations. See REASSESSMENT.md for exact counts and live Backend API smoke.
Earlier validation recorded **17 tests**, including original service SQL schemas and seeds on
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
