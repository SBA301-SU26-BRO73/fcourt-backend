# SCRUM-108 — public schedule notifications

Enable Spring profile `realtime` on booking-service and configure REDIS_HOST,
REDIS_PORT, REDIS_PASSWORD to the **same Redis** used by slot-hold-engine.
Set SLOT_ALLOWED_ORIGINS to the exact allowed web origin(s), comma-separated;
no wildcard is needed for native clients without an Origin header.

WebSocket endpoint: `/ws-fcourt` (native STOMP, no SockJS). Subscribe only to
`/topic/court-slots/{courtId}`. Payload is `{courtId:21,date:"2030-01-02"}`;
it is an invalidation of public availability, never a financial/booking command.
Client SEND and wildcard/private subscriptions are rejected. Messages are bounded.
The endpoint is public, matching the public schedule API. Gateway operators should
apply connection limits appropriate to their host capacity.

Hold acquire/release publish in the same Redis Lua execution as the change. A
conflict or repeated release does not notify. Booking cancellation and payment
projection notify **after DB commit**; Redis failure cannot reverse a committed
financial operation. Every booking pod subscribes to Redis, so two clients on
separate broker instances can receive the same hint. Redis Pub/Sub is not durable:
clients must fetch a fresh HTTP schedule on connect/reconnect and periodically.
Hold TTL expiry and facility locks are covered by the typed events below.

Configure a gateway WebSocket route with HTTP/1.1 upgrade headers and a read
idle timeout greater than the client's heartbeat. Example (merge with SCRUM-100):

```nginx
location = /ws-fcourt {
    set $booking http://booking-service:8088;
    proxy_pass $booking;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 60s;
}
```

## Typed events (second part of SCRUM-108)

Redis channel `fcourt:slot-changes`, STOMP topic `/topic/court-slots/{courtId}`. Payload: `{courtId, date, type[, lockType]}`.
Nothing else is ever forwarded (no tokens, phones, lock reasons/notes/contacts, branch/admin/booking ids).
Booking-service allow-lists `type` (unknown becomes `SLOT_CHANGED`) and `lockType`.

| type | producer | when |
|---|---|---|
| `HOLD_CREATED` | slot-hold-engine `acquire.lua` | online hold acquired (same Lua call) |
| `HOLD_RELEASED` | slot-hold-engine `release.lua` | online hold released |
| `HOLD_EXPIRED` | slot-hold-engine sweeper | hold TTL passed |
| `LOCK_CREATED` / `LOCK_REMOVED` | facility-service `LockService` | after the lock transaction commits; one event per (court, date) the window touches; `lockType` is `OUT_OF_SERVICE` or `PRIVATE_EVENT` (Full Court Lock) |
| `SLOT_CHANGED` | booking-service (cancel, payment projection) or unknown type | generic invalidation |

Clients treat every event as an invalidation and re-fetch the public schedule. `type`/`lockType` only select the
notice (for example purple "Bao san" for `PRIVATE_EVENT`).

### TTL expiry (slot-hold-engine)

Redis expires keys silently. Keyspace notifications need `notify-keyspace-events` (often unavailable on managed Redis)
and can be delayed, so they are not used. `acquire.lua` records each hold in the sorted set `fcourt:hold-expiry`
(score = expiry ms, member `courtId|date|tokenKey`), `release.lua` removes it, and `HoldExpirySweeper` runs `sweep.lua`
every `hold.expiry-sweep-ms` (default 1000; `hold.expiry-sweep-enabled=false` disables). The script is atomic, so with
several engine instances each expired hold is announced once. No Redis configuration change is needed.

Limits: holds created before this release and counter (walk-in) holds from `CounterHolds` are not indexed (the clients'
periodic resync covers them). A hold released through a path other than `release.lua` (sealed online booking release)
may produce one redundant `HOLD_EXPIRED` at its original expiry, which is harmless for an invalidation. Expected delay:
up to one sweep interval after the TTL.

### Facility locks (facility-service)

Opt in with `FACILITY_REALTIME_ENABLED=true` and `REDIS_HOST`/`REDIS_PORT`/`REDIS_PASSWORD` pointing at the same Redis
as slot-hold-engine and booking-service (default off; the service starts and its health stays UP without Redis).
`LockService` publishes after the `TransactionTemplate` returned, so a rollback or a 409 publishes nothing. A Redis
outage is logged and never fails the committed command. Court status changes and the SCRUM-114 reserve/commit/rollback
flow are out of scope.

### Latency harness (local, not acceptance)

`SlotFanoutLatencyTest` (no Docker: real STOMP stack, Redis hop bypassed) and `SlotRedisLatencyIntegrationTest`
(Docker: real Redis) run two real STOMP clients, send 200 samples (`-Dslot.latency.samples=N`) and write
`target/slot-latency-<label>.txt` with publish-to-receive p50/p95/max. Run `mvn test -Dtest=SlotFanoutLatencyTest` in
`booking-service`. Both run on loopback in one JVM, so they measure backend fan-out only, not Android devices, mobile
network, gateway or production.

Validation: booking Maven verify: 40 tests; hold Maven verify: 20 tests. Includes
real PostgreSQL/Redis and two independent native WebSocket clients receiving the
same redacted message. Fixtures establish transport and policy, not <500ms on two
Android devices, production reconnect behavior or facility lock notification.

References: [Spring STOMP](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-annotations.html).
