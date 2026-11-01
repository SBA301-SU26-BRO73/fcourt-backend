# POS retail Backend — SCRUM-106

Independent Spring Boot 3.5 / Java 21 service, port **8091**, PostgreSQL
**pos_retail_db**. Backend only; tablet/cart/Android UI belongs to Flutter repo.
Includes product catalog, stock adjustments, branch-scoped invoices, CASH sales
and durable booking-charge handoff. No reads/writes to another service's DB.

## Run and verify

Set POS_DATABASE_PASSWORD in the process environment, then:

```powershell
docker compose -f pos-retail-service/compose.yml up -d
mvn -f pos-retail-service/pom.xml spring-boot:run
Invoke-RestMethod http://localhost:8091/actuator/health
mvn -f pos-retail-service/pom.xml clean verify
```

Docker is required for real PostgreSQL/Kafka tests. Health returns only status.
Flyway initializes a new dedicated database; V1 preserves inherited POS schema,
V2 adds stock, item/name/price snapshots, command IDs, audit, outbox and settlement
inbox. Legacy products start with zero stock until explicitly adjusted. Existing
legacy databases require a reviewed V1 baseline; automatic baseline is disabled.

| Environment | Default / purpose |
|---|---|
| SERVER_PORT | 8091 |
| POS_DATABASE_URL | jdbc:postgresql://localhost:5491/pos_retail_db |
| POS_DATABASE_USER / POS_DATABASE_PASSWORD | postgres / required |
| POS_DATABASE_PORT | 5491, Compose host mapping only |
| AUTH_URL / STAFF_URL / FACILITY_URL / BOOKING_URL | localhost:8081 / 8090 / 8083 / 8088 |
| AUTH_JWK_SET_URI | http://localhost:8081/.well-known/jwks.json |
| AUTH_JWT_ISSUER / AUTH_JWT_AUDIENCE | http://localhost:8081 / fcourt |
| POS_BOOKING_SERVICE_TOKEN | required for booking charges; separate scoped service credential |
| POS_SETTLEMENT_SERVICE_TOKEN | required for internal settlement callbacks |
| KAFKA_BOOTSTRAP_SERVERS / POS_EVENTS_TOPIC | localhost:9092 / fcourt.pos.events |
| POS_DISPATCH_ENABLED | true; false disables scheduled delivery for tests |

Service credentials must be distinct, at least 32 characters, supplied securely
through deployment environment and used only over trusted TLS/private networking.
They are not user JWTs and are not stored in orders/events. Empty settlement
configuration rejects callbacks with 503; no booking credential rejects linked
booking creation before inventory changes. Outbound connect/read deadlines 2/3s.

## Authorization

User routes validate RS256 signature, issuer, audience, mandatory exp and positive
numeric sub, with roles/role claims. STAFF branch comes from live staff-service
/api/staff/me (including active account/assignment/branch checks). ADMIN product
commands require current ACTIVE admin and facility branch ownership. IDs/branch,
cashier, prices and final amounts are never trusted from client request fields.
Unavailable/malformed dependencies fail closed. Internal settlement has a
separate service-credential filter; STAFF/ADMIN JWTs do not grant settlement access.

## API

Business responses use no-store. Writes require UUID Idempotency-Key in addition
to Bearer JWT. Unknown JSON fields are rejected.

| Route | Access | Behavior |
|---|---|---|
| GET /api/staff/pos/products?query=&category=&limit=50&offset=0 | STAFF | Active assigned-branch catalog, live stock, exact category/literal substring search |
| POST /api/admin/pos/products | ADMIN | Create owned-branch product and initial stock |
| PUT /api/admin/pos/products/{id} | ADMIN | Replace name/unit/category/price/active/image; branch and stock immutable here |
| POST /api/admin/pos/products/{id}/stock-adjustments | ADMIN | Signed delta and reason; audited/idempotent, never negative stock |
| POST /api/staff/pos/orders | STAFF | CASH or BOOKING order with server snapshots and atomic stock decrement |
| GET /api/staff/pos/orders?limit=50&offset=0 | STAFF | Branch invoice history, two bulk queries |
| GET /api/staff/pos/orders/{id} | STAFF | Own-branch immutable price/name lines and current payment state |
| POST /api/internal/pos/settlements | Booking service credential | Idempotent batch settlement after authoritative payment |

Create product: branchId, name, unit (optional), category, price (>0, max 2 decimal
places), stockQuantity (0–1 billion), imageUrl (optional HTTPS URL). Images are
metadata references; no Cloudinary upload/download occurs here.

Stock adjustment: `{"delta":10,"reason":"restock"}`; nonzero delta required.
Order: `{"paymentMethod":"CASH","items":[{"productId":1,"quantity":2}]}`.
BOOKING additionally requires bookingId. Maximum 50 distinct products, quantities
1–10000, limit 100 and max offset 100000. Decimal totals must fit numeric(10,2).
Client price, totalAmount, status, branchId/cashier overrides are rejected.

## Inventory and payment semantics

- Products lock in ascending ID order. All product quantities, line snapshots,
  invoice, audit and outbox commit together. Missing/inactive/foreign/out-of-stock
  items roll back the entire cart. Same key/metadata replays without decrementing
  stock; changed metadata returns 409. Stock is immediately visible on GET.
- CASH returns **201 PAID** with paidAt: this records the cashier's cash collection,
  not an external bank transfer. Price/name/unit snapshots never change when the
  product is edited later.
- BOOKING returns **202 PENDING_CHARGE** with inventory reserved. Do not hand over
  goods until **ON_BOOKING** confirms durable attachment to the booking. Worker
  sends immutable charge/price lines using orderCode as idempotency key, then
  commits ON_BOOKING + an event. A definitive 422 rejection means no charge was
  committed: cancel the unissued order and return reservation to stock once.
- Timeouts/5xx/409/malformed acknowledgements retain the pending command; no
  automatic cancellation after ambiguous success. Same original payload/key
  retries after local rollback and does not reprice/recharge.
- Booking checkout includes attached charges atomically and reliably calls the
  settlement endpoint. Only matching ON_BOOKING orders become PAID. Callback
  event ID dedup and metadata matching prevent repeated or altered settlement.
- Per-row dispatch commits allow successful booking commands to progress even
  when unrelated Kafka delivery fails. Events are at least once, with stable IDs
  and monotonic order.version; consumers deduplicate and reject stale versions.

Pending charges must be monitored/reconciled; backend does not silently label
them paid or hand goods over during an outage. Broker replication/retention,
pending-age alerts and service credential rotation are deployment responsibilities.
See CONTRACTS.md and VALIDATION.md for proposed upstreams, evidence and limits.
