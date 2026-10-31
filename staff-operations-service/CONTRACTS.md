# Proposed staff integration contracts

Version 1, SCRUM-26. Project owner approved adapters and boundary stubs until
owning services exist. These endpoints are proposals, not implementations on dev.

## Authentication

RS256 JWT via AUTH_JWK_SET_URI, exact issuer, configured audience (fcourt by
default), mandatory exp, positive numeric sub, roles array or scalar role.
IAM GET /api/auth/me authenticates forwarded Bearer and returns
`{"id":1,"role":"ADMIN","status":"ACTIVE"}`. Every operation rechecks activity;
IAM must revoke access after disablement. Tokens/credentials are never persisted.

## Facility

GET /api/admin/branches/{branchId} authenticates ADMIN, enforces ownership and
returns `{"id":7,"adminId":1,"status":"ACTIVE"}`. Deleted/inactive branches must
not be returned as ACTIVE. Adapter independently checks ID, owner and activity.

GET /api/staff/branches/{branchId} authenticates STAFF and returns
`{"id":7,"status":"ACTIVE"}`. Staff service verifies its local assignment first.
Facility must not synchronously call /api/staff/me in this lookup (would recurse);
use an agreed assignment projection/separate internal boundary if needed.

## IAM staff commands

Require caller ADMIN Bearer; IAM independently enforces target STAFF account
belongs to caller's owned branch. Arbitrary user ID is never authorization.

- POST /api/internal/staff-accounts: branchId, email, password, fullName, phone;
  provision ACTIVE STAFF with branch ownership metadata.
- PUT /api/internal/staff-accounts/{id}: branchId, fullName, phone; immutable
  branch/email and authorization checked server-side.
- DELETE /api/internal/staff-accounts/{id}: disable managed STAFF; resolve branch
  server-side. Return status INACTIVE, including idempotent replay.

All success responses must contain the account, not 204:

```json
{"id":100,"email":"staff@example.com","fullName":"Staff member","phone":"0912345678","role":"STAFF","status":"ACTIVE"}
```

Header `Idempotency-Key: staff:<actorId>:<UUID>` must have durable, concurrent-safe
replay scoped to authenticated actor. Reject different operation/target metadata
with same key. Same create key returns same account; first credential wins and
retry is not password reset. Never store plaintext passwords in replay records.
Keep replay records long enough for recovery after ambiguous local failures.

401/403/404/409 map to equivalent errors. Unexpected 4xx, 5xx, timeout or invalid
response maps to 503. Never expose raw upstream body. IAM success can precede
local rollback: retry exact key/request to recover, never silently use a new key.
No distributed transaction is claimed. Account provisioning, ownership, JWKS and
revocation must be tested against actual services before enabling real traffic.
