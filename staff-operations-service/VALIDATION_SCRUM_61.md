# SCRUM-61 validation

Scope: Backend multi-slot walk-in, gRPC safety and server-priced cash invoice;
stacked on SCRUM-30 / PR #11.

Command: `mvn -f staff-operations-service/pom.xml -B -ntp clean verify`.

Executed: **85 tests passed, 0 failures/errors/skips**, executable JAR built;
service line coverage **99.02%**, coverage gate passed.

Verification includes generated protobuf clients on Windows/Java 21, real HTTP/2
gRPC fixture servers requiring authorization metadata, all-slot echo validation,
busy hold/booking rejection, malformed/unavailable replies and actual client
deadline expiration. HTTP tests verify contiguous multi-slot creation, invoice
cashier/line items/totals, duplicate/gapped selection, gRPC failure preventing
mutation and invalid cashier snapshot preventing local success. Pricing tests
verify fixed+percentage arithmetic, half-up rounding, zero default, policy
validation and numeric(10,2) overflow.

Inherited suite includes PostgreSQL migrations/transactions, signed JWT/security,
concurrent authority decisions, rollback/reconciliation and actual Kafka outbox.
Owning gRPC/booking/hold
services remain fixtures; final shared atomic reservation and real CASH settlement
still need actual integration. No absolute no-overbooking claim is made from
preflight reads alone; CONTRACTS.md describes the required authoritative guard.
