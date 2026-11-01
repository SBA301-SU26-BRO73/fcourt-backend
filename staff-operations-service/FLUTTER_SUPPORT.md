# Flutter staff support

## SCRUM-27 reactivation

`POST /api/admin/staff/{id}/reactivate`, no body, Bearer ADMIN and UUID
`Idempotency-Key`. Returns the existing StaffView; preserves branch, identity,
profile and work shifts. Replays are audited once, and altered-command keys
conflict. Owner/active-account/branch checks precede any write.

IAM boundary: `POST /api/internal/staff-accounts/{userId}/reactivate`, body
`{"branchId":7}`, same stable `staff:<actorId>:<UUID>` key and forwarded caller.
IAM must independently verify ownership and reactivate the managed STAFF account,
return its ACTIVE account projection, and guarantee durable replay. This is an
additional integration dependency, not an implemented IAM endpoint in this branch.
Missing/malformed/unauthorized IAM responses fail closed; local restoration only
follows validated IAM success. Retry the same key after ambiguous failures.

## Verification

2026-11-01: `mvn -f staff-operations-service/pom.xml verify` passes, including
real PostgreSQL and Kafka containers, HTTP/JWT fixtures and JaCoCo's >=80% service
coverage gate. Reactivation tests cover owner/role rejection, active assignment,
idempotent replay, one audit row and unavailable identity. Actual IAM/facility
integration and Android acceptance are not established by these fixtures.
