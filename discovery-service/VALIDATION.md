# Validation evidence

Executed on 2026-10-27 (Asia/Saigon), Windows 11, Oracle JDK 22.0.2, compilation release 21, Maven wrapper 3.9.11, Spring Boot 3.5.7, embedded PostgreSQL 14.15 and embedded Kafka 3.9.1. The Compose stack targets PostgreSQL 17; it was not launched because Docker Desktop was stopped. GitHub Actions runs the build on Temurin 21 separately.

## Build and functional tests

Command from discovery-service:

```powershell
.\mvnw.cmd verify '-Ddiscovery.benchmark=true' -B -ntp
```

Result: **BUILD SUCCESS; 14 tests, 0 failures, 0 errors, 0 skipped**.

- 1 unit test for event validation/serialization before projection.
- 2 application tests for repeated-query caching, failed-load recovery and an old in-flight read racing a projection revision.
- 11 integration tests using actual PostgreSQL/Flyway, Kafka and random-port HTTP: anonymous API, Vietnamese normalization, fuzzy/escaped input, correlated type/price filters, aggregate prices/counts, Haversine ordering, stale/deleted snapshots, transactional rollback/court replacement, deterministic pagination, HTTP 400 validation, cache invalidation after commit, actual DLT record publication and continued consumption after malformed input. The optional latency case is included in these 11 tests.

Default `verify` runs 13 tests and skips the optional benchmark. Detailed JUnit XML/text reports are generated under `target/surefire-reports`; the executable JAR is `target/discovery-service-0.0.1-SNAPSHOT.jar`. Target output is intentionally not committed.

`git diff --check` and `docker compose -f discovery-service/compose.yml config --quiet` also pass. The Postman collection is supplied for manual reproduction; the Postman application and Android emulator were not used. No upstream facility producer was available to test end-to-end publication; integration tests publish the agreed snapshot payload directly through Kafka.

## Local HTTP latency

10,000 synthetic active branches, 50,000 courts; loopback HTTP; one sequential client. Per scenario: one reported cold request, 20 warmup requests and 100 measured warm requests, default page size 20. The cache holds 1,000 entries for 5 seconds. Results include HTTP round trip and JSON serialization.

| Scenario | Cold request (ms) | Warm p50 (ms) | Warm p95 (ms) | Warm max (ms) |
| --- | ---: | ---: | ---: | ---: |
| List branches | 66.25 | 10.90 | 16.71 | 31.26 |
| Fuzzy `brach` (no results at default word threshold) | 81.42 | 7.29 | 8.43 | 11.54 |
| Broad query `branc` | 78.60 | 7.01 | 9.60 | 18.84 |
| City + type + min price | 44.36 | 7.08 | 8.48 | 17.15 |
| GPS distance order | 39.26 | 7.04 | 8.44 | 16.87 |

Warmed p95 and all measured warm requests were below 80 ms. **The strict <80 ms acceptance condition for every request remains open:** the cold no-result fuzzy request was 81.42 ms; previous cold runs were also slower. A single cold request per scenario is diagnostic, not a cold latency distribution. No concurrent production-load or network-distance SLA is claimed. Validate with real data, cold-query mixes and deployment concurrency before acceptance; cache TTL expiration and projection commits cause fresh database loads.

The final query materializes branch matches once, returning count and page in one statement even for empty pages. Only page branches receive court aggregation. Normalization resolves to bound values before the indexed search. These changes reduce cold work without changing public filter semantics.

## Integration items remaining

- SCRUM-14/SCRUM-16 facility owners must publish full snapshots, including court/pricing changes, monotonically increasing aggregate versions, initial/recovery replay and deletion state. Legacy Branch has ward but no district/GPS; enrich these upstream, preserving null when unavailable.
- Provision source/DLT partitions and production database extension permissions; configure secured production credentials/brokers.
- With multiple replicas in one consumer group, other replicas may serve cached data until the configured TTL. This is the documented eventual-consistency window.
- Run the Compose image stack and Postman collection when Docker is available; verify GitHub Actions separately.
- Create/link the two Jira review tickets and obtain Minh's actual approval. Neither approval, merge nor Jira status changes are performed by this implementation.

## Layered package refactor verification

After moving source into controller, dto/request, dto/response, model/event, service, repository, component, config and exception packages, `mvnw.cmd clean verify -B -ntp` completed successfully on 2026-10-27. Result: 13 functional/unit/integration tests passed, 0 failures/errors; the optional performance benchmark was skipped (14 discovered tests, 1 skipped). Main and test classes were rebuilt from a clean target directory to verify package imports and Spring component discovery. This refactor changes organization only; the latency measurements above are from the earlier benchmark run.
