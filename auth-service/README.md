# auth-service

Spring Boot 3.5 / Java 21 / JdbcTemplate / Flyway. Port **8081**, database **auth_db**.

Current scope: **SCRUM-8 super admin** (approve or reject court owners, lock or unlock accounts).
Register, login, token issuing and JWKS (**SCRUM-6**) are not in this service yet; see
[What SCRUM-6 must wire](#what-scrum-6-must-wire).

## Run locally

```bash
# Postgres with an auth_db database, Kafka reachable at KAFKA_BOOTSTRAP_SERVERS
export AUTH_DATABASE_URL=jdbc:postgresql://localhost:5432/auth_db
export AUTH_DATABASE_PASSWORD=...            # no default on purpose
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export AUTH_JWK_SET_URI=http://localhost:8078/.well-known/jwks.json   # dev token tool until SCRUM-6
./mvnw spring-boot:run
```

| Variable | Default |
|---|---|
| `SERVER_PORT` | `8081` |
| `AUTH_DATABASE_URL` / `AUTH_DATABASE_USER` / `AUTH_DATABASE_PASSWORD` | `jdbc:postgresql://localhost:5432/auth_db` / `postgres` / (required) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `AUTH_JWK_SET_URI` | `http://localhost:8081/.well-known/jwks.json` |
| `AUTH_JWT_ISSUER` / `AUTH_JWT_AUDIENCE` | `http://localhost:8081` / `fcourt` |
| `AUTH_EVENTS_ENABLED` / `AUTH_EVENTS_TOPIC` / `AUTH_EVENTS_DELAY_MS` | `true` / `fcourt.auth.events` / `1000` |

Tests: `./mvnw clean verify` (Docker required: Testcontainers Postgres and Kafka).

## Security

Same resource-server setup as the other services: `Authorization: Bearer <RS256 JWT>` checked
against `AUTH_JWK_SET_URI`, issuer and audience. `sub` must be a positive numeric user id; the role
comes from the `roles` array or a single `role` claim. `/api/super-admin/**` requires
`SUPER_ADMIN`, and the service also checks in `users` that the caller is still an ACTIVE super admin
(a locked super admin with an unexpired token gets 403). Errors are RFC 7807 `ProblemDetail`
(`{type,title,status,detail,instance,errors?}`), including 401 and 403.

## API (SUPER_ADMIN only)

| Method | Path | Body | Result |
|---|---|---|---|
| GET | `/api/super-admin/court-admins/pending` | | `CourtAdminResponse[]`, oldest first |
| GET | `/api/super-admin/court-admins/{id}` | | `CourtAdminResponse` (404 if not a court owner) |
| PUT | `/api/super-admin/court-admins/{id}/approve` | `{reason?}` (optional, max 500) | `CourtAdminResponse`, status `ACTIVE`, emits `OwnerApprovedEvent` |
| PUT | `/api/super-admin/users/{id}/approve` | same | alias used by `tools/system_tests` |
| PUT | `/api/super-admin/court-admins/{id}/reject` | `{reason}` (required, 1..500) | `CourtAdminResponse`, status `REJECTED` |
| PUT | `/api/super-admin/users/{id}/toggle-status` | `{reason?}` | `{id, status}`: `ACTIVE` to `LOCKED` and back |

`CourtAdminResponse`: `{id, email, fullName, phone, role, status, createdAt, documents:[{id, url, docType}]}`,
where `docType` is `LEGAL` (business licence or identity document) or `COURT_IMAGE`.

Rules:
- approve/reject only apply to role `ADMIN` users in `PENDING_APPROVAL`; any other status gives 409, and a
  non-owner id gives 404.
- toggle-status only switches `ACTIVE` and `LOCKED`; other statuses give 409, and `SUPER_ADMIN` accounts
  (including yourself) cannot be locked (409).
- Validation errors give 400 with `errors:[{field, message}]`.
- Every decision writes `user_account_action_logs` (`APPROVE`, `REJECT`, `LOCK`, `UNLOCK`; previous and new
  status, reason, performed_by_id = caller).

## Events

Approval appends to the `auth_outbox` table in the same transaction; `OutboxRelay` publishes pending
rows to Kafka topic `fcourt.auth.events` (key = userId, acks=all) and marks them published only after
the broker acknowledges them. Delivery is at-least-once, so consumers must dedupe on `eventId`.

```json
{"eventId":"b6c9aabf-...","type":"OwnerApprovedEvent","version":1,
 "data":{"userId":17,"approvedBy":16,"approvedAt":"2026-10-31T10:01:52.342Z"}}
```

facility/subscription services can consume it to enable branch creation for the owner.

## Database (Flyway)

- `V1`, `V2`: ported from `database/V1__init_schema.sql` and `V2__seed_sample_data.sql`, with the psql-only
  `\restrict` / `\unrestrict` lines removed (Flyway cannot run them).
- `V3`: adds `REJECTED` to `users_status_check`, an index for the pending queue, and `auth_outbox`.
- `V4`: demo data with the same password hash as V2: super admin `superadmin@demo.com` (id 16) and
  pending owners 17, 18 and 19 with documents.

## What SCRUM-6 must wire

- Issue RS256 tokens (`iss` = `AUTH_JWT_ISSUER`, `aud` = `fcourt`, numeric `sub`, `roles`) and serve
  `/.well-known/jwks.json`. With that in place, leave `AUTH_JWK_SET_URI` at its default so the service
  validates its own tokens.
- Add the public routes (`/api/auth/**`, `/.well-known/jwks.json`) to the `permitAll` list in
  `config/SecurityConfiguration`. Everything else is `denyAll` by default.
- On owner registration, create the user as `ADMIN` + `PENDING_APPROVAL` and insert the uploaded files
  into `user_documents` (`LEGAL` / `COURT_IMAGE`) so they show up in the review queue.
- Login should refuse `PENDING_APPROVAL`, `REJECTED` and `LOCKED` accounts. Tokens that are already
  issued stay valid until they expire, so keep access tokens short-lived.
- Start new migrations at `V5`. `UserRole` / `UserStatus` in `com.fcourt.auth.user` are shared.
