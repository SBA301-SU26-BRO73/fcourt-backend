# subscription-service

Spring Boot 3.5 / Java 21 / JdbcTemplate / Flyway. Port **8082**, database **subscription_db**.

Scope: **SCRUM-10** catalogue (court types and SaaS subscription plans, managed by the super admin) and
**SCRUM-45** buying and renewing a plan through PayOS.

## Run locally

```bash
# Postgres with a subscription_db database; auth-service running for token validation
export SUBSCRIPTION_DATABASE_URL=jdbc:postgresql://localhost:5432/subscription_db
export SUBSCRIPTION_DATABASE_PASSWORD=...    # no default on purpose
./mvnw spring-boot:run
```

| Variable | Default |
|---|---|
| `SERVER_PORT` | `8082` |
| `SUBSCRIPTION_DATABASE_URL` / `SUBSCRIPTION_DATABASE_USER` / `SUBSCRIPTION_DATABASE_PASSWORD` | `jdbc:postgresql://localhost:5432/subscription_db` / `postgres` / (required) |
| `AUTH_JWK_SET_URI` | `http://localhost:8081/.well-known/jwks.json` (auth-service) |
| `AUTH_JWT_ISSUER` / `AUTH_JWT_AUDIENCE` | `http://localhost:8081` / `fcourt` |
| `PAYOS_CLIENT_ID` / `PAYOS_API_KEY` / `PAYOS_CHECKSUM_KEY` | (none) secrets from the PayOS dashboard, environment only |
| `PAYOS_RETURN_URL` / `PAYOS_CANCEL_URL` | (none) where PayOS sends the owner after paying or cancelling |
| `PAYOS_API_URL` / `PAYOS_LINK_TTL` | `https://api-merchant.payos.vn` / `15m` |
| `PAYOS_AMOUNT_OVERRIDE` | empty. Sandbox only: charge this fixed VND amount instead of the plan price |
| `SUBSCRIPTION_TIME_ZONE` | `Asia/Ho_Chi_Minh` (subscriptions run on calendar dates in this zone) |

Checkout answers 503 until the three PayOS secrets and both redirect URLs are set. Configure the webhook URL
`https://<host>/api/subscriptions/payment-webhook` in the PayOS dashboard.

Tests: `./mvnw clean verify` (Docker required for the `*IntegrationTest` classes: Testcontainers Postgres).

## Security

Same resource-server setup as the other services: `Authorization: Bearer <RS256 JWT>` issued by
auth-service and checked against its JWKS, issuer and audience. `sub` must be a positive numeric user id;
the role comes from the `roles` array or a single `role` claim. Errors are RFC 7807 `ProblemDetail`
(`{type,title,status,detail,instance,errors?}`), including 401 and 403. Validation errors give 400 with
`errors:[{field, message}]`. Routes that are not listed below are denied.

The role is taken from the token only (auth-service owns accounts and this service must not read its
database), so a super admin that gets locked keeps write access until the token expires (15 minutes).

| Who | Can |
|---|---|
| anyone | read court types, `GET /api/subscription-plans/all` |
| any signed-in user | read the paged plan list and a plan by id |
| `SUPER_ADMIN` | create, update and delete court types and plans |
| `ADMIN` (court owner) and `SUPER_ADMIN` | `/api/subscriptions/**`: own subscription, checkout, own payments |
| PayOS (no token) | `POST /api/subscriptions/payment-webhook`, authenticated by its HMAC signature |

## API

### Court types

| Method | Path | Body | Result |
|---|---|---|---|
| GET | `/api/court-types?active=&page=&size=` | | `Page<CourtTypeResponse>`; `active` omitted returns both |
| GET | `/api/court-types/{id}` | | `CourtTypeResponse` (404 if missing or deleted) |
| POST | `/api/court-types` | `CourtTypeRequest` | `201 CourtTypeResponse` |
| PUT | `/api/court-types/{id}` | `CourtTypeRequest` | `CourtTypeResponse` (full replacement) |
| DELETE | `/api/court-types/{id}` | | `204` (soft delete) |

`CourtTypeRequest`: `{name (required, max 100), nameEn? (max 100), description? (max 2000), icon? (max 50), color? (max 20),
active? (default true on create, unchanged on update when omitted)}`

`CourtTypeResponse`: `{id, name, nameEn, description, icon, color, active, createdAt, updatedAt}`

### Subscription plans

| Method | Path | Body | Result |
|---|---|---|---|
| GET | `/api/subscription-plans/all` | | `SubscriptionPlanResponse[]`: active plans only, cheapest monthly price first (pricing page) |
| GET | `/api/subscription-plans?page=&size=` | | `Page<SubscriptionPlanResponse>`: every plan that is not deleted, active or not, by id |
| GET | `/api/subscription-plans/{id}` | | `SubscriptionPlanResponse` |
| POST | `/api/subscription-plans` | `SubscriptionPlanRequest` | `201 SubscriptionPlanResponse` |
| PUT | `/api/subscription-plans/{id}` | `SubscriptionPlanRequest` | `SubscriptionPlanResponse` (full replacement) |
| DELETE | `/api/subscription-plans/{id}` | | `204` (soft delete) |

`SubscriptionPlanRequest`: `{name (required, max 100), tagline? (max 255), monthlyPrice? (>= 0, VND), yearlyPrice? (>= 0, VND),
maxBranches (required, >= 1), maxCourts (required, >= 1), features? (max 30 strings of max 255), color? (max 20),
popular? (default false), active? (default true on create, unchanged on update when omitted)}`

`SubscriptionPlanResponse`: `{id, name, tagline, monthlyPrice, yearlyPrice, maxCourts, maxBranches, active, color, popular,
features:[string], createdAt, updatedAt}`. A null price means the plan is not sold for that cycle.

### Subscriptions and payments (court owner)

| Method | Path | Body | Result |
|---|---|---|---|
| GET | `/api/subscriptions/my-subscription` | | `SubscriptionResponse` |
| POST | `/api/subscriptions/checkout` | `{planId, billingCycle}` | `201 PaymentResponse` with the PayOS `checkoutUrl` and `qrCode` |
| GET | `/api/subscriptions/payments/{orderCode}` | | `PaymentResponse` (404 for someone else's payment; super admin sees any) |
| POST | `/api/subscriptions/payment-webhook` | PayOS payload | `200 {received:true}` |

`billingCycle`: `MONTHLY` or `YEARLY` (case-insensitive). Poll the payment with `GET .../payments/{orderCode}` after the
owner returns from PayOS: `status` becomes `PAID` once the webhook has been processed.

`SubscriptionResponse`: `{id, adminId, plan: SubscriptionPlanResponse, maxCourts, maxBranches, startDate, endDate, active}`.
`active` is true until `endDate` has passed. `currentBranchCount` / `currentCourtCount` of the old monolith are gone: they live in
facility-service now.

`PaymentResponse`: `{id, orderCode, planId, planName, billingCycle, amount (VND), status, checkoutUrl, qrCode, expiredAt, paidAt,
subscriptionActive}`. `status`: `PENDING`, `PAID`, `EXPIRED`, `FAILED` (`CANCELLED` is allowed by the schema but nothing sets it yet; an owner who cancels at
PayOS simply sees `EXPIRED` later). `checkoutUrl` and `qrCode` are only present while `PENDING`.

#### Purchase rules

- The amount is the plan price for the cycle (`monthlyPrice` or `yearlyPrice`), never a value from the client. A plan with no
  price for that cycle, or an inactive or deleted plan, cannot be bought (400 / 404).
- The checkout link lives 15 minutes. A `PENDING` payment past its deadline reads as `EXPIRED`.
- The first time an owner without any subscription calls `my-subscription` they get a one year starter subscription on the
  cheapest plan on sale. If every plan has lapsed, the latest one is returned with `active: false`.
- Paying for the plan the owner already has extends it from its current end date (no paid days are lost) and refreshes the
  plan limits. Paying for a different plan ends the live subscriptions yesterday and starts the new one today.
- A month or year is a calendar month or year (31 January plus one month is 28 February).

#### Webhook

PayOS signs `data` with HMAC-SHA256 over its fields sorted by name (`key=value` joined with `&`; null, `"null"` and
`"undefined"` as empty, arrays as JSON with sorted keys, as in the PayOS SDKs) using the checksum key. The
endpoint verifies the signature, then the order code and that `amount` equals the checkout amount, then marks the payment `PAID`
and activates the subscription in one transaction. A repeated delivery does nothing, even when two arrive at the same time.
A verified `code 00` is honoured even if our own 15 minute timer already ran out, because PayOS only sends it after the money
arrived.

Payloads that cannot be trusted (bad signature, unknown order code, amount mismatch) are logged and still answered `200` so the
endpoint tells a caller nothing. Any other failure (for example the database is down) answers 5xx so PayOS retries.

### Pages

`Page<T>`: `{content:[T], page (zero-based), size, totalElements, totalPages}`. `page` defaults to 0 and `size` to 10;
out of range values are clamped (`page >= 0`, `1 <= size <= 100`) instead of rejected. Timestamps are ISO-8601 with offset.

## Rules

- Names are trimmed and unique among rows that are not deleted (409 `Court type name already exists` /
  `Subscription plan name already exists`). A deleted row frees its name.
- Delete is a soft delete (`deleted_at`): subscriptions that already bought a plan keep resolving it.
- Plan features: blank entries are dropped, the rest are trimmed and de-duplicated in the order given; PUT replaces
  the whole list.
- Optional text fields that are blank are stored as null.
- A court type that is deleted here is not checked against courts in facility-service (no cross-service reads).

## Database (Flyway)

- `V1`, `V2`: ported from `database/V1__init_schema.sql` and `V2__seed_sample_data.sql`, with the psql-only
  `\restrict` / `\unrestrict` lines removed (Flyway cannot run them). The seed has 6 court types and 3 plans.
- `subscriptions` and `subscription_payments` (unique `order_code`) are used by the purchase flow. Start new migrations at `V3`.
- `admin_id` is the auth-service user id from the token's `sub`; there is no foreign key across services.
