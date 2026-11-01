# SCRUM-63 validation

Scope: Backend weekly matrix and realtime warning flags; stacked on PR #12.

Executed `mvn -f staff-operations-service/pom.xml -B -ntp clean verify`:
**100 tests pass, zero failures/errors/skips**, executable JAR and coverage gate
passed. Includes real PostgreSQL/Kafka/gRPC, signed-JWT HTTP boundary fixtures.
Service line coverage: **99.16%**.

New tests: empty days, fixed staff branch despite foreign query parameter, invalid
range/missing dates, strict 15-minute late boundary, inclusive 10-minute ending
window/exclusive end, configurable threshold, terminal states and midnight end.

Authenticated HTTP benchmark: 10 courts × 7 days × 32 slots/day = 2240 slots,
15 warm-ups then 30 sequential measured requests including HTTP decoding. Java
21.0.11, 12 available processors, Docker Desktop/PostgreSQL 16, local HTTP boundary
servers, relay disabled in request tests. Measured p50 **30.10 ms**, p95
**41.40 ms**, max **44.92 ms**. Test asserts warmed p95 <250ms; raw report is
target/schedule-benchmark.json. Query shape is fixed bulk I/O, not per-slot calls.

This is a local single-client measurement with fixture upstreams. It does not
establish cold-start/production/concurrent-client latency or the actual booking
service's query performance. Live integration and distributed state convergence
remain pending owning-service implementation; no production readiness claim.
