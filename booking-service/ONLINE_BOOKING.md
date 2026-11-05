# Online booking authority — SCRUM-91

The owner approved implementing the missing online creation step as part of the
integration task. This is additive to the existing Staff counter authority.

## Public contract

`POST /api/bookings` requires CUSTOMER Bearer and UUID `Idempotency-Key`:

```json
{"holdToken":"<public hold UUID>","courtId":21,"date":"2030-01-01","slotIds":[1,2]}
```

Return 201 and the existing BookingView, initially PENDING_PAYMENT. Required
account activity/role is rechecked with IAM. Customer identity comes from the
verified JWT, contact from the held selection, venue metadata/prices from
facility. Client price, actor or branch overrides are rejected as unknown fields.
Selections must be unique, contiguous, future, available and at most 48 slots.
The total must fit numeric(10,2). Retries with the same key/selection return the
same resource ID and its current state; changed identity returns 409.

## Atomicity and recovery

- Booking Flyway V7 adds actor-scoped command replay and a durable hold-release
  queue. Its unique hold hash affects only new online commands, preserving
  compatibility with legacy booking snapshots.
- Creation takes the same court/day PostgreSQL advisory lock as walk-in, checks
  overlapping active time intervals, then claims the public Redis capability.
  All known booking writers share this protocol; availability reads are hints.
- Hold claim requires the dedicated booking credential, validates the whole
  selection and seals it to actor/command with at least 30 seconds remaining.
  Public release cannot delete a sealed capability. Every slot owner is checked
  atomically; another actor/key or changed selection is rejected.
- Booking, slots, replay and release queue commit in one PostgreSQL transaction.
  Only the SHA-256 hold hash is persisted. No distributed transaction is claimed.
  A rollback leaves the sealed lease recoverable with the same command until
  expiry; a lost success response replays the committed booking.
- After durable creation, a worker releases only matching Redis ownership.
  PostgreSQL occupancy continues to reject competing bookings. Failed or
  ambiguous release is retried; it never deletes another holder's slots.
- Expired PENDING_PAYMENT bookings are cancelled only after payment's guarded
  expiry decision. Payment uses the same advisory lock as receipt submission and
  approval, so an already accepted bill is retained even if its Kafka projection
  has not arrived. Unavailable payment fails closed and retries later.
- Expiry deactivates live slots and appends BookingCancelledEvent atomically.
  Existing payment events confirm/cancel bookings with inbox deduplication.

## Service configuration

| Setting | Owning processes / purpose |
|---|---|
| BOOKING_HOLD_SERVICE_TOKEN | Same secret on booking/hold; at least 32 bytes |
| PAYMENT_BOOKING_SERVICE_TOKEN | Same separate secret on booking/payment; at least 32 bytes |
| AUTH_URL / FACILITY_URL / HOLD_URL / PAYMENT_URL | Trusted internal service origins; localhost defaults are local development only |
| booking.online-release-enabled | true by default; durable release worker |
| booking.online-expiry-enabled | true by default; guarded expiry worker |

Deploy V7 and the owning hold/facility/payment additions together. Supply secrets
through server configuration and private/TLS service networking, never Flutter
or public clients. Creation fails closed if required credentials are missing.

New internal boundaries:

- Facility GET `/api/internal/courts/{id}/booking-context`: active public venue
  metadata only, under its existing internal-GET network policy; no bank/admin ID.
- Hold POST `/api/internal/online-holds/claim` and `/release`: server credential,
  no user-accessible metadata read. Release accepts the durable hold hash.
- Payment POST `/api/internal/payments/booking-expiry`: server credential,
  booking ID/original expiry; return matching ID and mayExpire under its DB lock.

Inspect pending `online_hold_releases` and expired pending bookings when an
authority is unavailable. Do not clear replay ledgers or release leases manually
to hide failures. Production monitoring, migration rollout and human approval
remain deployment/review responsibilities.
