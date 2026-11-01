# Discovery service

Independent Spring Boot 3 / Java 21 service on port **8085**. Public branch search reads only `discovery_db`; it never queries facility tables or requires a JWT. PostgreSQL `pg_trgm` provides fuzzy matching and `unaccent` normalizes Vietnamese text.

## Source structure

Packages follow the original backend's layer-based organization:

```text
src/main/java/com/fcourt/discovery/
|-- DiscoveryApplication.java
|-- component/          # Kafka event listener
|-- config/             # Kafka retry and dead-letter configuration
|-- controller/         # Public HTTP endpoints
|-- dto/
|   |-- request/        # Search parameters and validation
|   `-- response/       # Paginated branch summaries
|-- exception/          # HTTP exception handling
|-- model/
|   `-- event/          # Kafka snapshots and projection change events
|-- repository/         # JDBC search and transactional read-model writes
`-- service/            # Search orchestration and cache consistency
```

Tests for components and services mirror their packages; the full integration test stays at the application root. PostgreSQL read models are managed by JDBC/Flyway, so no JPA entity layer is introduced.

## Run locally

From this directory, with Docker Desktop running:

```powershell
docker compose up -d postgres kafka topics
$env:DISCOVERY_DB_URL = 'jdbc:postgresql://localhost:5545/discovery_db'
$env:DISCOVERY_DB_USER = 'discovery'
$env:DISCOVERY_DB_PASSWORD = 'discovery_local'
.\mvnw.cmd spring-boot:run
```

Alternatively, run the entire stack: `docker compose --profile app up -d --build`.
The separate Compose project uses PostgreSQL port 5545 to avoid the repository database on 5432. Kafka uses port 9092. These are local development credentials only. Production must supply credentials and broker security separately. Flyway installs the read model and extensions; its database role needs extension creation permission, or an administrator must preinstall `pg_trgm` and `unaccent`.

Publish the sample snapshot from PowerShell (the key is branch ID):

```powershell
$event = Get-Content -Raw examples/branch-updated.json
"1|$($event.Trim())" | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:19092 --topic facility.branch-updated.v1 --property parse.key=true --property key.separator='|'
curl.exe 'http://localhost:8085/api/public/branches?query=sunrize'
```

Import `examples/discovery.postman_collection.json` into Postman; the collection includes normal, fuzzy, combined-filter, GPS and invalid-request examples. No authorization header is needed. The service is eventually consistent: wait for Kafka consumption before searching. Increment `version` when republishing modified sample data.

## Public API

`GET /api/public/branches`

| Parameter | Meaning |
| --- | --- |
| `query` | Name/address substring or fuzzy word match; case/accent insensitive; max 200 characters |
| `city`, `district` | Exact comparison after trimming, lowercasing and removing accents |
| `courtTypeId` | Positive court type ID |
| `minPrice`, `maxPrice` | Inclusive overlap with one court's published price range; nonnegative, up to 2 decimal places |
| `latitude`, `longitude` | Optional coordinate pair, ranges -90..90 and -180..180 |
| `page`, `size` | Zero-based page, default 0; size 1..100, default 20 |

Type and price filters must match the **same court**. Summaries always show the whole branch's active court count and overall min/max price. A court without pricing still counts, but does not match a price filter. Price ranges summarize active slot templates, not real-time availability or a guaranteed booking quote.

Response fields: `content`, `page`, `size`, `totalElements`, `totalPages`. Each item includes `id`, `name`, `address`, `city`, `district`, `latitude`, `longitude`, `minPrice`, `maxPrice`, `courtCount`, `distanceKm`. Empty branches have count 0 and null prices. Missing GPS yields null distance.

With user GPS, sort by Haversine distance (Earth radius 6371.0088 km), unknown branch locations last. Otherwise sort by fuzzy relevance when a query is present, or branch ID. Branch ID breaks ties for stable pagination. Invalid parameters return HTTP 400 with a problem detail. Inactive/deleted branches never appear.

## Facility event contract

Topic: `facility.branch-updated.v1` (override `BRANCH_UPDATED_TOPIC`). Key: decimal branch ID string. Value: UTF-8 JSON, see `examples/branch-updated.json` and `src/main/java/com/fcourt/discovery/model/event/BranchUpdatedEvent.java`.

- Full snapshot fields: `branchId`, strictly increasing per-branch `version`, `name`, `address`, `city`, optional `district`, optional coordinate pair, `active`, `deleted`, `courts`.
- `courts` contains every ACTIVE, non-deleted court; IDs must be unique. Each entry includes `id`, `courtTypeId`, and either an ordered `minPrice`/`maxPrice` pair or both null. Derive prices from active, non-deleted `time_slot_templates` across days/times. Do not publish private bank/admin data.
- Emit after branch, court, pricing, status or deletion changes. Facility owns durable outbox delivery, aggregate version allocation and initial/recovery snapshot replay. A timestamp alone is not a safe version allocator.
- Projection atomically replaces court rows only for newer versions. Replays/old versions are ignored; a deleted branch leaves an inactive version tombstone so older data cannot resurrect it. A later, higher version may explicitly restore it.
- Consumer commits record offsets after processing; transient failures retry twice at one-second intervals. Invalid JSON/validation failures skip retries and go to `<topic>.DLT`; failed DLT publication must not acknowledge recovery. Provision the DLT with at least the source partition count. Inspect/fix/replay DLT records operationally.
- Keep branch events keyed by branch ID for partition ordering. The SQL version guard also handles out-of-order delivery. Do not delete version tombstones without a replay strategy.

**Dependency boundary:** the supplied legacy backend has `ward`, but no district/GPS fields and no `BranchUpdatedEvent` producer. The target facility service currently has SQL only. This implementation supplies the consumer/read model and a runnable sample publisher; automatic upstream publication requires SCRUM-14/SCRUM-16 owners to adopt this contract. Do not infer district from ward or query another service's database. Missing district/GPS remain null.

## Search cache and consistency

The application layer keeps at most 1,000 query/page results in Caffeine, expiring 5 seconds after load (`DISCOVERY_CACHE_TTL_SECONDS`). Equal concurrent requests share a load. A successful projection commit advances the cache revision and invalidates local entries. A database rollback never invalidates; an old in-flight read cannot populate the new revision. With multiple instances in the same Kafka consumer group, only the consuming instance gets the local invalidation, so other instances can serve cached data until TTL expiration. Discovery remains eventually consistent.

Repeated popular searches are fast, but a cold broad fuzzy search can exceed 80 ms on this machine. The benchmark reports a cold request separately from warmed p50/p95/max. A strict <80 ms SLA for every cold query and production concurrency remains an acceptance/load-test item; do not infer it from warmed local measurements.

## Verify

```powershell
.\mvnw.cmd verify
.\mvnw.cmd verify '-Ddiscovery.benchmark=true'
```

On Linux/macOS use `sh mvnw`. Tests launch isolated PostgreSQL and Kafka processes on random ports; Docker is unnecessary. The first run downloads Maven, dependencies and platform-specific PostgreSQL binaries. Avoid running as root on Linux because PostgreSQL initdb rejects root.

Coverage includes real Flyway/PostgreSQL queries, anonymous HTTP and validation, Vietnamese/fuzzy matching, price/type correlation, aggregate counts, Haversine ordering, pagination, cache invalidation and racing reads, atomic rollback, stale/deleted events and Kafka poison-message DLT recovery. The optional benchmark seeds 10,000 branches / 50,000 courts, warms 20 requests and measures 100 sequential HTTP requests per scenario, asserting warmed p95 <80 ms and reporting cold requests separately. It is a local single-client check, not a production concurrency SLA; see `VALIDATION.md` for measured evidence.

## Configuration

`SERVER_PORT` (8085), `DISCOVERY_DB_URL` (localhost:5432/discovery_db), `DISCOVERY_DB_USER`, `DISCOVERY_DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS` (localhost:9092), `BRANCH_UPDATED_TOPIC`. Consumer group is `discovery-service-v1`, with earliest offset for a new group. Health endpoint: `/actuator/health`. No cross-service foreign keys, auth dependency or shared entity module.
