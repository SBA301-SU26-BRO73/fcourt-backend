# SCRUM-106 Backend validation

Executed on 2026-10-31:
`mvn -f pos-retail-service/pom.xml -B -ntp clean verify`.

**40 tests passed, 0 failures/errors/skips**, executable JAR built, >=80% service
coverage gate passed. JaCoCo lines: service **98.33%**, persistence **100%**,
integration **98.15%**, config/security **97.78%**, API **100%**.
Java 21.0.11, Maven 3.9.12, PostgreSQL 16 and Kafka native 3.8 Testcontainers.

Coverage includes real HTTP/RSA JWT/JWKS signature/issuer/audience/expiry/subject
checks; role/branch/active-account isolation; product/stock idempotency and audit;
immutable invoice snapshots; server totals and decimal overflow; six duplicate
concurrent sales; 12 competing sales against five stock units; multi-item and
forced-outbox rollback; booking pending/attachment/settlement states; ambiguous
charge success/local failure retry; exactly-once inventory compensation after
definitive rejection; changed settlement event identity/wrong amount or service
credential denial. Cash request timing is asserted below 10 seconds locally.
Also covers exact decimal totals, reverse-cart lock ordering, ambiguous booking
errors retaining reservations and full settlement-batch rollback on a late conflict.

Real Kafka consumer observes the acknowledged CASH event after simulated broker
failure/recovery, with stable order key and no credentials in payload. Per-row
dispatcher commits successful commands independently; monotonic order versions
protect reporting from old events. Tests use actual PostgreSQL migrations and
transactions, not H2 equivalents.

Auth/staff/facility/booking are boundary fixtures. Live CASH cash collection,
booking charge/checkout atomicity, deployed credential/TLS enforcement, reporting
consumers and Flutter goods handoff/UI still need owning-service integration.
No real bank transfer, external Cloudinary call, production deployment, Jira
status change or peer approval has been performed/claimed.
