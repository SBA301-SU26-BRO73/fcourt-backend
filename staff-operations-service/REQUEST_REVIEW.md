# SCRUM-26 — Staff management review preparation

Source: https://bro73.atlassian.net/browse/SCRUM-26

This is a local preparation document. No Jira request, notification, assignment,
approval or status change has been made.

## Why

Court administrators need to manage STAFF accounts assigned to their own branch.
Staff operations must enforce the active branch assignment on every request.

## Scope

- Spring Boot 3 / Java 21 service on port 8090, own PostgreSQL staff_db.
- Administrative CRUD at /api/admin/staff, work-shift data, account disablement.
- IAM and facility adapters; no writes into their databases.
- JWT verification, ownership authorization, fail-closed dependency handling.
- Repeatable database, HTTP, security and concurrency tests.

## Proposed contracts requiring owning-service integration

- Auth JWT verification and claims; STAFF account provisioning/update/disable
  endpoints and idempotency/compensation protocol.
- Facility ownership and active-branch lookup.
- Invalidation of staff access after account/assignment disablement.
- Work-shift input semantics and whether schedule editing is assignment metadata
  or a separate operational scheduling feature.

The existing payment PR proposes RS256/JWKS, issuer/audience validation, a numeric
subject and roles/role claims. This proposal is not an implemented auth contract.
The owner approved defining the contracts and testing with boundary stubs.
CRUD now uses these adapters and fail-closed ownership/activity checks. Actual
auth/facility integration and Flutter login remain pending owning services.

The Jira description mentions Request Review assigned to Minh before coding and
Request Review Coding after the PR. Approval has not been claimed or backdated.
