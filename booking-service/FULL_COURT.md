# SCRUM-114 — full-court pricing preview and atomic reservation

> Status update (SCRUM-242): the reservation described under "Reservation" below replaces the 503 stub that the original
> draft returned. The sections after it are the original preview contract and the evidence that led to the facility
> authority (SCRUM-241, facility-service `court-reservations`); read them as history where they say "not integrated".

## Reservation (SCRUM-242)

`POST /api/admin/bookings/full-court` (ADMIN, header `Idempotency-Key: <uuid>`) takes the quote fields plus
`contactName` and `contactPhone`:

```json
{"branchId":7,"courtIds":[1,2],"date":"2030-01-02","startTime":"08:00","endTime":"09:00",
 "contactName":"Giai dau noi bo","contactPhone":"0901234567"}
```

Saga: server price (the quote, frozen in `full_court_packages`) -> facility `reserve` (locks every court, visible to
discovery/hold/counter/online as OUT_OF_SERVICE) -> ONE booking-DB transaction (advisory lock `counter:<court>:<date>` per
court, same namespace as the counter and online paths; re-check `booking_slots`; one CONFIRMED booking per court with its
slots and share of the package total/discount; package -> BOOKED) -> facility `commit`.

* 201 created and locked; 200 idempotent replay; 202 booked but the facility commit is not confirmed yet (`lockState`
  PENDING_COMMIT, finished by retrying the same key or by the reconciler) or lost (`LOST`, lease expired; the slots stay
  blocked by `booking_slots`, review manually); 409 conflict (facility lock, existing booking, changed payload for the
  key, or still being processed); 503 when facility cannot answer.
* Why the booking DB stays the final arbiter: a walk-in or online booking that reaches `booking_slots` first makes the
  package transaction fail and the facility reservation is rolled back; if facility lost its lock the unique index still
  prevents a double booking. slot-hold-engine needs no change: a Redis hold that slipped in just fails at booking time.
* Failure before BOOKED: facility `rollback` (idempotent, tombstones unknown ids), command -> FAILED. A rollback that cannot
  be confirmed leaves RESERVING and `FullCourtReconciler` retries it. Only success is memoised: retrying a FAILED key
  starts a new attempt with a new reservation id.
* The package discount is split across member bookings (floor per court, remainder to the last court) so member totals
  add up exactly to the quoted total. `bookings.original_amount/discount_amount` are voucher-only (check constraint) and
  stay null; the package totals live in `full_court_packages`.
* Config: `FACILITY_URL`, `FACILITY_RESERVATION_TOKEN` (same value as facility-service, >= 32 chars; unset -> 503),
  `booking.full-court.reconcile-enabled`, `booking.full-court.reconcile-delay-ms`.
* Out of scope (not in SCRUM-114 acceptance): deposit, e-contract/invoice, cancelling a whole package (cancelling one member
  booking does not release the facility lock yet), Flutter UI.

---
## Original preview contract and gate evidence (history)


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
