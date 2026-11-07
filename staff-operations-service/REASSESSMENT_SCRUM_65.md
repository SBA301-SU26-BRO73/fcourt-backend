# SCRUM-65 — opaque ticket contract, 2026-11-05

Source: exported Jira description dated 2026-11-05, assignee Phạm Đăng Khoa.
Base: dev at 4fa603b. The updated description requires STAFF Bearer and UUID
Idempotency-Key and explicitly does not require an FC- prefix.

The production code already accepts opaque ticket strings. This change adds
regression coverage and corrects documentation; it does not alter admission rules.

- Staff HTTP tests send two exact 32-digit codes, including leading zeros and
  surrounding whitespace. The upstream sees the unchanged numeric string.
- Same-key retries return the original result with one audit/outbox record;
  new-key manual and QR attempts cannot admit the same ticket again.
- Missing idempotency header and ADMIN/CUSTOMER requests fail before admission.
- Booking authority tests resolve both numeric codes from PostgreSQL, replay the
  same command and reject a subsequent QR command against the same booking.
- Existing tests retain wrong date/state, foreign branch, legacy aliases,
  concurrent QR/manual and audit rollback/recovery coverage.

Validation: Java 21, Docker Desktop, real PostgreSQL 16 and Kafka containers.
`mvn -f booking-service/pom.xml -B -ntp clean verify`: 36 tests passed.
`mvn -f staff-operations-service/pom.xml -B -ntp clean verify`: 128 tests passed on retry
with the service coverage gate. The initial run failed to start its Kafka
container, before tests; it is not counted as a successful run.

These are Backend contract/integration tests, not Android device or production
acceptance. Request Review/Request Review Coding belongs to Nguyên according to
the supplied description; no human approval or Jira request is fabricated here.
