# auth-service

Spring Boot 3.5 / Java 21 / JdbcTemplate / Flyway. Port **8081**, database **auth_db**.

Scope: **SCRUM-6** (register, login, JWT, refresh token, role guard) and **SCRUM-8** super admin
(approve or reject court owners, lock or unlock accounts).

## Run locally

```bash
# Postgres with an auth_db database, Kafka reachable at KAFKA_BOOTSTRAP_SERVERS
export AUTH_DATABASE_URL=jdbc:postgresql://localhost:5432/auth_db
export AUTH_DATABASE_PASSWORD=...            # no default on purpose
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
./mvnw spring-boot:run
```

Without `AUTH_JWT_PRIVATE_KEY` the service signs with an ephemeral key generated at startup (a warning is
logged): fine locally, but tokens stop validating on restart and instances would not share keys. Generate a
stable key once with
`openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048` and pass the PEM in the variable.

| Variable | Default |
|---|---|
| `SERVER_PORT` | `8081` |
| `AUTH_DATABASE_URL` / `AUTH_DATABASE_USER` / `AUTH_DATABASE_PASSWORD` | `jdbc:postgresql://localhost:5432/auth_db` / `postgres` / (required) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `AUTH_JWK_SET_URI` | `http://localhost:8081/.well-known/jwks.json` |
| `AUTH_JWT_ISSUER` / `AUTH_JWT_AUDIENCE` | `http://localhost:8081` / `fcourt` |
| `AUTH_JWT_PRIVATE_KEY` / `AUTH_JWT_KEY_ID` | PKCS8 PEM RSA key (ephemeral when blank) / thumbprint of the key |
| `AUTH_JWT_ACCESS_TTL` / `AUTH_JWT_REFRESH_TTL` | `15m` / `30d` |
| `AUTH_CLOUDINARY_URL` | `cloudinary://key:secret@cloud` for court owner documents; uploads give 503 when blank |
| `AUTH_PASSWORD_RESET_TTL` | `30m` |
| `AUTH_EVENTS_ENABLED` / `AUTH_EVENTS_TOPIC` / `AUTH_EVENTS_DELAY_MS` | `true` / `fcourt.auth.events` / `1000` |

Tests: `./mvnw clean verify` (Docker required: Testcontainers Postgres and Kafka).

## Security

Same resource-server setup as the other services: `Authorization: Bearer <RS256 JWT>` checked
against `AUTH_JWK_SET_URI`, issuer and audience. `sub` must be a positive numeric user id; the role
comes from the `roles` array or a single `role` claim. `/api/super-admin/**` requires
`SUPER_ADMIN`, and the service also checks in `users` that the caller is still an ACTIVE super admin
(a locked super admin with an unexpired token gets 403). Errors are RFC 7807 `ProblemDetail`
(`{type,title,status,detail,instance,errors?}`), including 401 and 403.

## API (public and signed-in users)

| Method | Path | Body | Result |
|---|---|---|---|
| POST | `/api/auth/register/customer` | `{fullName, email, phone, password}` | `201 AuthResponse`, account `ACTIVE` |
| POST | `/api/auth/register/court-owner` | multipart: `fullName, email, phone, password, legalDocuments[]` (1..5), `courtImages[]` (0..10) | `201 {id, status:"PENDING_APPROVAL", message}`, no session |
| POST | `/api/auth/login` | `{email, password}` | `AuthResponse` |
| POST | `/api/auth/refresh` | `{refreshToken}` | `AuthResponse` (the old refresh token is revoked) |
| POST | `/api/auth/logout` | `{refreshToken}` | `204`, idempotent |
| GET | `/api/auth/profile` | | `UserResponse` (needs a Bearer token) |
| PATCH | `/api/auth/profile` | `{fullName, phone}` | `UserResponse` |
| POST | `/api/auth/password/forgot` | `{email}` | `202 {message}`, the same answer for any email |
| POST | `/api/auth/password/reset` | `{token, newPassword}` | `200 {message}`; 400 when the token is unknown, used or expired |
| PATCH | `/api/auth/password` | `{currentPassword, newPassword}` | `AuthResponse` (needs a Bearer token) |
| GET | `/.well-known/jwks.json` | | public keys, `Cache-Control: max-age=300` |

`AuthResponse`: `{accessToken, refreshToken, tokenType:"Bearer", expiresIn (seconds), user:{id, email, fullName, phone, role, status, createdAt}}`.
There is no `data` envelope; errors are the same RFC 7807 problems as everywhere else.

Rules:
- Email is trimmed and lower-cased; email and phone must be unique (409). Phone: `0` or `+84` then 8 to 10
  digits. Password: 8 to 72 characters (BCrypt only hashes the first 72 bytes).
- Customers register `ACTIVE` and get a session straight away. Court owners (`ADMIN`) register
  `PENDING_APPROVAL`, their files go to Cloudinary and into `user_documents` (`LEGAL` / `COURT_IMAGE`), and
  they cannot sign in until a super admin approves them. Documents: JPEG, PNG, WebP or PDF, 5 MB each; a failed
  upload rolls the whole registration back.
- Login gives the same 401 for an unknown email and a wrong password (and spends the same hashing time).
  Only after the password is correct does it refuse `PENDING_APPROVAL`, `REJECTED`, `LOCKED` and `INACTIVE`
  accounts with 403 and a reason.
- Access tokens are RS256 with `iss`, `aud`, numeric `sub`, `roles` and a 15 minute lifetime. Refresh tokens
  are opaque random values stored only as a SHA-256 hash in `refresh_tokens`, valid for 30 days, and work
  once. Presenting a revoked token again is treated as theft and revokes every session of that user.
  Refreshing for a locked account also revokes its sessions.
- Forgot password never reveals whether an email is registered. Only `ACTIVE` accounts get a token, at most
  one request per minute per account is honoured, and a new request invalidates the earlier token. The token
  is random, valid for 30 minutes, works once and is stored only as a SHA-256 hash in
  `password_reset_tokens`. It leaves the service only inside `PasswordResetRequestedEvent`; the notification
  service builds the emailed link from it.
- Reset sets the new password and revokes every refresh token of the user. Change password needs the current
  password, rejects reusing it, revokes every session and returns a fresh `AuthResponse` so the caller stays
  signed in on this device while other devices have to sign in again.
- An access token that is already issued stays valid until it expires even if the account is locked later,
  except on `/api/auth/profile` and `/api/super-admin/**`, which re-check the account in the database.

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

Approval and password reset requests append to the `auth_outbox` table in the same transaction;
`OutboxRelay` publishes pending rows to Kafka topic `fcourt.auth.events` (key = userId, acks=all) and marks
them published only after the broker acknowledges them. Delivery is at-least-once, so consumers must
dedupe on `eventId`.

```json
{"eventId":"b6c9aabf-...","type":"OwnerApprovedEvent","version":1,
 "data":{"userId":17,"approvedBy":16,"approvedAt":"2026-10-31T10:01:52.342Z",
         "email":"owner06@demo.com","fullName":"Chu San Sau"}}

{"eventId":"0f3c1d52-...","type":"PasswordResetRequestedEvent","version":1,
 "data":{"userId":7,"email":"customer@demo.com","fullName":"An Nguyen",
         "token":"<url-safe random, 43 chars>","expiresAt":"2026-11-01T10:30:00Z"}}
```

facility/subscription services can consume `OwnerApprovedEvent` to enable branch creation for the owner.
`email` and `fullName` were added to it for the notification service (additive, still version 1).
`PasswordResetRequestedEvent.token` is a secret: consumers must not log it, and topic access should be
restricted to the notification service.

## Database (Flyway)

- `V1`, `V2`: ported from `database/V1__init_schema.sql` and `V2__seed_sample_data.sql`, with the psql-only
  `\restrict` / `\unrestrict` lines removed (Flyway cannot run them).
- `V3`: adds `REJECTED` to `users_status_check`, an index for the pending queue, and `auth_outbox`.
- `V4`: demo data with the same password hash as V2: super admin `superadmin@demo.com` (id 16) and
  pending owners 17, 18 and 19 with documents.

## Notes for the next tasks

- Leave `AUTH_JWK_SET_URI` at its default so the service validates its own tokens against its own JWKS.
- Everything not listed in `SecurityConfiguration` is `denyAll`: add new routes there.
- `V5` creates `refresh_tokens` and `V6` creates `password_reset_tokens`; start new migrations at `V7`.
  `UserRole` / `UserStatus` in `com.fcourt.auth.user` are shared.
- `SessionService.closeAll(userId)` revokes every session of a user.
- Expired and revoked `refresh_tokens` rows and used or expired `password_reset_tokens` rows are not purged yet.
