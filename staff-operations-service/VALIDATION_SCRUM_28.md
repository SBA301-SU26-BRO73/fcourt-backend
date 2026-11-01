# SCRUM-28 validation

Scope: schedule/today and QR check-in Backend, stacked on SCRUM-26 (PR #9).
The complete suite runs Java 21, PostgreSQL 16 and Kafka native 3.8 containers.

Command: `mvn -f staff-operations-service/pom.xml -B -ntp clean verify`.

Executed result: **49 tests passed, 0 failures/errors/skips**, executable JAR
built; service line coverage **98.23%**, coverage gate passed.

Tests cover business-day selection across UTC midnight; assigned branch schedule
and no-store; malformed dates/states/duplicate courts or slots; actual signed JWT
role checks inherited from SCRUM-26; check-in success, same-key replay and
different-key concurrent double check-in; wrong branch/date and upstream
rejection; command/outbox rollback and reconciliation after remote success.

Kafka/PostgreSQL tests verify acknowledged publication, broker failure retaining
pending rows and actual duplicate delivery with the same event ID when the DB
fails after Kafka acknowledgement. Consumer deduplication is required; no
exactly-once claim is made. Kafka tests use the official Testcontainers Kafka
module: https://java.testcontainers.org/modules/kafka/.

Auth/facility/booking boundaries are stubbed contract servers. Actual booking
compare-and-set/idempotency, deployed role/branch enforcement and booking/reporting
consumers remain integration dependencies. No Jira status/approval is implied.
