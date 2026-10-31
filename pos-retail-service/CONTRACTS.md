# Proposed POS contracts — SCRUM-106 Backend

Owner approved defining adapters with boundary fixtures until owning services
are implemented. These are not endpoints already proven against dev/production.

## User authorization reads

- STAFF GET staff-service /api/staff/me forwards caller JWT; returns positive
  userId/branchId and active=true. It must validate live IAM activity, fixed
  assignment and branch activity, as implemented/proposed in staff PRs #9–#14.
- ADMIN GET auth /api/auth/me verifies current id/role ADMIN/status ACTIVE.
  GET facility /api/admin/branches/{id} enforces ownership and returns
  id/adminId/status ACTIVE; deleted branches must not look active.
- GET booking /api/internal/bookings/{id}/pos-context forwards caller STAFF JWT;
  authorize its active branch and return id/branchId/status CONFIRMED or CHECKED_IN.
  This is a preflight, not a guaranteed reservation against concurrent checkout.

JWT: RS256/JWKS, configured issuer/audience, exp, numeric user sub, roles/role.
No employee credential is persisted for background work or forwarded to Kafka.

## Durable booking charges

POST booking /api/internal/bookings/{id}/pos-charges uses dedicated scoped
POS_BOOKING_SERVICE_TOKEN and Idempotency-Key=orderCode. Body: orderCode,
branchId, cashierUserId, amount and immutable items (productId/name/unit/quantity,
priceAtOrder/lineTotal). Identity/price snapshot was authorized and committed
by POS; booking verifies the trusted POS service and branch/booking policy.

Return orderCode, bookingId, branchId, amount, status ACCEPTED. The authority must
atomically serialize attachment with checkout, use durable idempotency and replay
original acceptance after timeout even if booking has since completed. The same
key with altered metadata cannot create a second charge. Do not trust arbitrary
unauthenticated cashier/branch/amount IDs. Use TLS/private service transport.

422 is a durable, definitive rejection: **no charge committed for this key**,
including on every retry. It must never replace an earlier ACCEPTED decision.
This permits returning a reservation to stock before goods were issued. Other
errors/timeouts are ambiguous and remain pending for retry/reconciliation.
If checkout wins the race after context lookup, reject the new attachment with
422; unissued inventory is compensated. If attachment wins, checkout must include
that charge atomically in the payment, rather than silently completing unpaid.

## Settlement after checkout

Booking owns a durable outbox/retry for POST POS /api/internal/pos/settlements,
authenticated by separate POS_SETTLEMENT_SERVICE_TOKEN. Do not substitute an
employee JWT. Credentials must be at least 32 characters and deployment-managed.

```json
{"eventId":"00000000-0000-4000-8000-000000000001","bookingId":42,"branchId":7,"paidAt":"2030-01-01T12:00:00Z","orders":[{"orderCode":"POS-<uuid>","amount":20000}]}
```

Maximum 100 distinct order codes per callback. Paid time cannot be >60s in future.
Only matching BOOKING invoices in ON_BOOKING or already PAID are accepted;
branch/booking/amount must match. All orders and inbox event commit atomically;
same event ID+body replays 204, altered event metadata is 409. Unknown/pending
orders return 409 and caller retries after attachment commits. The worker locks
the order during remote attachment so an early callback cannot bypass that state.

PosOrderPaidEvent is emitted only after local PAID commit. Callback failures never
erase the authoritative booking payment; caller retains and retries its outbox.

## Reporting events

Topic POS_EVENTS_TOPIC (default fcourt.pos.events), key orderCode. Envelope has
eventId, type, version 1 (schema), order snapshot. order.version is the monotonic
aggregate version, separate from schema version. Types: PosOrderCreatedEvent
(pending booking), PosBookingChargeAttachedEvent, PosOrderPaidEvent,
PosOrderCancelledEvent. Consumers deduplicate eventId and apply only newer
aggregate versions; delivery across retries/instances can duplicate/reorder.

POS commits its outbox with inventory/order state and marks only acknowledged
Kafka deliveries. Partial worker failures retain failed rows without undoing
other independently committed dispatches. Consumers, actual booking charge and
settlement implementation, physical goods handoff and production networking
remain integration acceptance items, not established by contract fixtures.
