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
Hold TTL expiry and facility locks currently need that reconciliation; facility
PR #87 has no durable lock-change event contract on dev. The task's immediate
full-court lock push criterion remains outstanding until that producer is integrated.

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

Validation: booking Maven verify: 40 tests; hold Maven verify: 20 tests. Includes
real PostgreSQL/Redis and two independent native WebSocket clients receiving the
same redacted message. Fixtures establish transport and policy, not <500ms on two
Android devices, production reconnect behavior or facility lock notification.

References: [Spring STOMP](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-annotations.html).
