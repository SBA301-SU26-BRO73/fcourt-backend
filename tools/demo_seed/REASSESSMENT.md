# SCRUM-92 reassessment — 2026-11-05

Baseline: dev 4fa603b; source: exported Jira task assigned to Phạm Đăng Khoa.

The previous seed populated database rows but left runtime payment checkout,
ticket/QR and customer metadata empty. It also lacked the requested all-day
booking and seeded discounts that the current checkout service does not apply.

Owner decisions in this session:

- "Bao sân" means one booking renting every slot of a day, not a new booking
  status or a maintenance feature. It is CONFIRMED on demoDate+1, court 1.
- Vouchers remain available and unused; every payable amount matches slot sums.
- Local fixture data may be generated for testing. No external bank/Cloudinary
  ownership or production acceptance is inferred from a local pass.

The updated dataset contains 31 bookings, 82 booking slots and 31 corresponding
payments. Confirmed ticket codes are the same 32-digit string in both services;
payment snapshots deserialize into the current API models. Phones, bank-account
format and court base prices match current service validation. Runtime-v2
fingerprints reject old datasets rather than rewriting them.

## Executed validation

- `python -m unittest discover -s tools/demo_seed -p test_seed.py -v`: **22 tests
  pass**, including real PostgreSQL 16, all current owning-service migrations,
  schema preflight, fractional money, rerun/resume, collision and non-overwrite.
- `runtime_smoke.py` ran **real Booking, Facility and Payment jars** against
  disposable PostgreSQL/Redis. The final run used jars built from dev baseline
  in this worktree; no dependency on the SCRUM-91 feature branch is required.
- Real APIs returned the seeded numeric ticket, generated a PNG QR, returned a
  pending checkout for 100000 and returned the complete 32-slot all-day ticket.
- The test helper manually applies the exact current migrations in its own new
  databases and disables Flyway only for those disposable processes. It never
  baselines or changes a user database. All its processes/containers are cleaned.

Example (Windows, Java 21, Docker; build the three jars first):

```powershell
python tools/demo_seed/runtime_smoke.py --booking-jar booking-service/target/booking-service-0.0.1-SNAPSHOT.jar --facility-jar facility-service/target/facility-service-0.0.1-SNAPSHOT.jar --payment-jar payment-service/target/payment-service-0.0.1-SNAPSHOT.jar --output tools/demo_seed/target/runtime-api
```

JWKS signing and image/bank assets are explicit fixtures. This test does not
contact Cloudinary, transfer money or establish production readiness. Real venue
assets and on-device image acceptance remain external validation gates.
