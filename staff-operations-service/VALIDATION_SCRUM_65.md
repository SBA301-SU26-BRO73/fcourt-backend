# SCRUM-65 validation

Scope: Backend manual check-in with transactional actor audit, stacked on PR #13.

Command: `mvn -f staff-operations-service/pom.xml -B -ntp clean verify`.

Executed **104 tests passed, zero failures/errors/skips**, executable JAR built;
service line coverage **99.16%**, coverage gate passed.

New tests verify manual success/idempotency, unknown/blank code and authority
date/state conflict, simultaneous QR/manual requests allowing one success only,
and a forced audit-write failure rolling back local success followed by recovery
with the same remote command key. Audit records actor/branch/booking/channel,
not raw code or token. Flyway V4 is applied on real PostgreSQL.

All earlier HTTP/JWT, gRPC, PostgreSQL/Kafka, CASH pricing and weekly benchmark
tests remain included. Actual booking authority/IAM/facility remain boundary
fixtures; live integration and Flutter keyboard/scanner UX are not claimed.
