# Slot hold engine — SCRUM-24

Spring Boot 3 / Java 21, port **8087**, Redisson, Redis **7**. Holds are
all-or-nothing and expire after **300 seconds**, without a cleanup job.

```sh
# From fcourt-backend; requires Java 21, Maven and Docker
docker compose -f slot-hold-engine/compose.yml up -d
mvn -f slot-hold-engine/pom.xml spring-boot:run
curl http://localhost:8087/actuator/health
```

Configuration: `SERVER_PORT` (8087), `REDIS_ADDRESS` (`redis://localhost:6387`),
`REDIS_PASSWORD` (empty locally), `DISCOVERY_URL` (`http://localhost:8085`).
Redis is bound to loopback locally and uses AOF and `noeviction`. Production
must supply Redis authentication and private networking. This service does not
need PostgreSQL; the existing SQL is legacy reference, not a live hold store.

## API

`POST /api/bookings/hold-slots` supports guests and signed-in customers supplying
their contact phone. Identity is not inferred from a client-provided customer ID.

```json
{"courtId":1,"date":"2030-01-01","slotIds":[1,2],"guestPhone":"0912345678"}
```

`201 Created`:

```json
{"holdToken":"<uuid>","expiresAt":"<UTC ISO instant>","expiresInSeconds":300,"courtId":1,"date":"2030-01-01","slotIds":[1,2]}
```

`400`: invalid contact, IDs, empty/duplicate selection, more than 48 slots,
or a past date. `404`: court missing. `409`: slot held, booked, closed or expired.
`503`: discovery or Redis unavailable, or discovery response violates the contract.
There is no waiting or retry loop on a conflicting hold.

## Proposed discovery contract — integration pending SCRUM-22

Discovery must implement `GET /api/public/courts/{courtId}/slots?date=YYYY-MM-DD`
returning an array, with unique IDs and the canonical 30-minute slot start:

```json
[{"slotId":1,"status":"AVAILABLE","startTime":"18:00:00"}]
```

Only `AVAILABLE` slots strictly in the future in `Asia/Ho_Chi_Minh` are accepted.
Other states (`HOLD`, `BOOKED`, `OUT_OF_SERVICE`, `EXPIRED`) fail closed. The
gateway verifies availability before atomically acquiring Redis keys. SCRUM-22
and SCRUM-24 have a circular Jira dependency; the proposed contract is tested
with an HTTP stub until the actual discovery implementation lands.

## Redis protocol and booking integration

Redisson executes `redis/acquire.lua` atomically on a **single Redis primary**.
Slot keys: `fcourt:hold:{courtId}:{date}:{slotId}`, value: the opaque hold token.
Token metadata: `fcourt:hold-token:{token}`, value: the request JSON. Every key
uses the same absolute expiration from Redis's own clock. Treat tokens as bearer
secrets; never expose them via public discovery or logs. Metadata expires with
the selection, including its contact phone.

Booking/payment integration must verify token ownership of **all** selected keys
and complete its durable booking before releasing or expiring the hold. That
commit protocol and durable BOOKED ownership belong to booking/payment tasks;
this endpoint alone does not implement payment confirmation. All booking writers
must coordinate with the same lock protocol and discovery must report durable
bookings accurately. Redis Cluster and asynchronous-failover guarantees are not
provided by this single-primary implementation.

## Validation

```sh
mvn -f slot-hold-engine/pom.xml clean verify
```

Tests use an isolated Redis 7 Testcontainer (Docker required) and a stubbed
discovery HTTP boundary. They cover concurrent acquisition across two Redis
clients, common 300-second expiration, re-acquisition of expired slots, no partial
writes, contact/selection validation, busy/expired slots and dependency failures.
They do not claim end-to-end validation with SCRUM-22 or a completed payment flow.
