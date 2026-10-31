# SCRUM-30 validation

Scope: Backend CASH walk-in and checkout, stacked on SCRUM-28 / PR #10.

Command: `mvn -f staff-operations-service/pom.xml -B -ntp clean verify`.
Executed: **63 tests passed, zero failures/errors/skips**, executable JAR built,
service line coverage **98.05%**; coverage gate passed. No live-system acceptance
is implied.

New cases: CASH CHECKED_IN creation, completion/replay, read-only reconciliation
before availability, unavailable slot states, foreign court/date/client-price
rejection, six concurrent walk-ins competing for one authoritative slot,
rollback after remote creation and recovery when that slot now reports BOOKED,
wrong-branch checkout and failed dependency without success event. Public
discovery fixtures reject any forwarded Authorization header. Malformed discovery
time/state/price fails closed; canonical final slot can end at midnight.

Retains the full staff security/JWT, PostgreSQL transaction and real Kafka outbox
suite from earlier PRs. Contract server enforces an atomic booking decision in
tests; production correctness requires the actual booking authority/shared hold
protocol and CASH settlement integration described in CONTRACTS.md. No changes
are made to booking/payment databases or another owner's service implementation.
