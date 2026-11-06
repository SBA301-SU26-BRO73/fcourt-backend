# SCRUM-114 — pricing preview and reservation contract

This draft implements a **preview**, not completed full-court reservation. No
booking, facility lock, deposit, contract or payment is created. The commit is safe
to review before facility integration; SCRUM-114 acceptance remains incomplete.

`POST /api/admin/bookings/full-court/quote` accepts:

```json
{"branchId":7,"courtIds":[1,2],"date":"2030-01-02","startTime":"08:00","endTime":"09:00"}
```

Requires an ADMIN JWT and current facility ownership of the active branch/courts.
The request must contain unique positive court IDs, a future venue-local day within
31 days and a same-day 30-minute-aligned interval. Cross-midnight/multiple-day
packages are not supported in this preview. Every selected half-hour must exist
and be available in both canonical facility and current discovery schedules.
Amounts come from facility's canonical schedule, never a client price or percent.

Server properties `booking.full-court.minimum-hours` (default 4 court-hours) and
`booking.full-court.discount-percent` (default 0) configure the percentage policy.
Discount applies when total court-hours across the selected courts reach the
threshold; the discount rounds down to two decimal places using BigDecimal. Invalid
server policy fails at startup. Confirm the policy with the product owner before
setting a nonzero discount. Fixed package deals still need a reviewed pricing
contract and are intentionally not invented here.

The response always states `reserved:false` and
`reservationState:ATOMIC_AUTHORITY_REQUIRED`. A quote is a snapshot; it cannot prove
the slots will remain free after the response. No hold token or bank details are
returned. The intended command `POST /api/admin/bookings/full-court` returns 503
`FULL_COURT_ATOMIC_AUTHORITY_REQUIRED`, without side effects, until the reservation
authority is integrated. Never turn this response into a client-side success.

## Evidence for the integration gate

Facility PR #87 (`feature/Tuan-SCRUM-49-111-lock-out-of-service`) implements branch
row locking and transactional lock insertion, but `LockService.lockBranch` invokes
`requireNoBookings` **before** the transaction. Public hold checks availability
before Redis acquire. Those operations do not share the same atomic authority;
checking a snapshot, then calling the lock API does not prevent a concurrent
hold/booking. PR #87 also lacks an idempotent reservation/commit/rollback protocol
for the booking package. Calling its create-lock route then attempting a booking
write would risk orphaned locks or duplicate reservations after retries.

## Required contract before enabling reservation

Agree one authority used by every hold, counter booking and private-event lock.
It must reserve the whole court/date/slot selection atomically, verify canonical
availability and reject conflicts; a branch DB transaction alone is insufficient.
Persist command identity and request fingerprint, return the same operation on
replay, reject changed payload, and expose operation status after network timeout.
Define lease/commit/cancel transitions, fencing and crash recovery so a committed
reservation cannot silently expire and a failed booking cannot leave indefinite
locks. Capture the server quote/policy in the durable package command; do not
reprice a replay. Use service APIs/events rather than cross-database writes.

Only after this contract is reviewed should booking materialize package/member
bookings, record reliable outbox events and coordinate confirmation/compensation.
Then test concurrent package versus public hold/counter booking, same-command
replay, changed payload, network timeout, authority restart, partial failure and
rollback; verify history, pricing, unlock and payment integration. Electronic
contracts/deposits need their own approved data/provider contracts.

Local validation: full booking Maven verify passed (42 tests); three additional
HTTP authority contract tests verify ownership, foreign court and malformed-response
handling. These tests do not certify distributed reservation or production.
