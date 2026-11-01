# [Review] BE Discovery - Initialize service and public branch search

Prepared review content; no Jira ticket or approval has been created by this work.

- Work type: Request Review
- Assignee: Minh (Leader), as explicitly requested in the task (overrides the convention examples naming Nguyen).
- Parent: task order #15/111, Sprint 1 #15/34. Confirm its Jira key; the ordinal does not establish SCRUM-15.
- Estimate: 1 day.

## Work to implement

- [ ] Independent Spring Boot 3 / Java 21 service, port 8085, own PostgreSQL database.
- [ ] Read model for branches/courts, accent-insensitive substring/fuzzy matching with pg_trgm.
- [ ] Full BranchUpdatedEvent snapshots, increasing branch versions, atomic replacement and tombstones.
- [ ] Anonymous GET /api/public/branches with name/address query, city/district, court type, prices, pagination and Haversine distance.
- [ ] Unit, database, HTTP and Kafka/DLT tests; repeatable local latency measurement.

## Team impact

- Depends on SCRUM-14 and SCRUM-16; facility currently supplies SQL rather than a running producer.
- Agree district/GPS enrichment and published active-court prices from time_slot_templates with the facility owner.
- Facility owns outbox publication after branch/court/pricing/status changes, monotonic aggregate versions and replay/bootstrap.
- No cross-service database reads, shared entities, booking or auth business logic.
- Service bootstrap exceeds the convention's approximate 300-line review target; review the staged commits. Leader decides any ticket split.

## Review workflow

The convention expects Request Review before coding and leader approval before In Progress. The user explicitly requested implementation and a Jira instruction file afterward. Do not backdate a ticket or claim prior approval. Record the actual chronology when creating the review tickets, then request code review against the real PR.
