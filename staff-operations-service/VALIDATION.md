# SCRUM-26 validation

Date: 2026-10-31. Branch: feat/scrum-26-staff-management.

## Executed

`mvn -f staff-operations-service/pom.xml -B -ntp clean verify`

Result: **34 tests, 0 failures, 0 errors, 0 skipped**, packaged executable JAR.
Java 21.0.11, Maven 3.9.12, Spring Boot 3.5.16, Docker Engine 29.5.3,
PostgreSQL 16 Alpine Testcontainers. JaCoCo line coverage: service 97.33%,
persistence 100%, integration adapters 96.72%, security/config 100%.
Coverage gate requires service package >=80%.

- Real Flyway V1/V2, generated identity, unique user assignment.
- Real HTTP with RSA JWT/JWKS: signature, issuer, audience, expiry, missing exp,
  malformed subject and wrong roles rejected.
- Create/list/update/disable, fixed branch, active staff self-lookup, no-store.
- Ownership isolation across administrators, disabled account/branch denial.
- Body/header/page bounds, shift overlap/time precision, unknown field rejection,
  UTF-8 password byte limit and no password echo/persistence.
- Six concurrent identical creates produce one upstream account request and one
  local assignment; completed replay and mismatched metadata handled.
- Forced local audit failure rolls back assignment/commands; retry recovers the
  same IAM account using its stable scoped idempotency key.
- Unexpected upstream errors, malformed responses and identity mismatch fail
  closed without inserting staff rows.

`git diff --check` is part of pre-push verification. The GitHub workflow runs the
same Maven command for affected PRs; remote results are checked separately.

## Limits and follow-up

IAM and facility are HTTP boundary servers in tests, not production services.
Actual account provisioning/disablement, JWKS/claims, ownership, durable IAM
idempotency/recovery and Flutter login require owning-service integration.
Production data, Cloudinary and AWS endpoint are not available from the owner.
No full-system or production readiness is claimed. This PR does not implement
schedule, booking/check-in or POS tasks, which have their own branches/PRs.

No Jira status, review request, assignment or approval has been changed.
