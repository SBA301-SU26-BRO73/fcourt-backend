# Booking history — SCRUM-53

Independent Spring Boot 3 / Java 21 service on port 8088 with PostgreSQL `booking_db`.
`mvn -f booking-service/pom.xml spring-boot:run` uses `DATABASE_URL`,
`DATABASE_USER`, `DATABASE_PASSWORD` (default local PostgreSQL port 5488).
Flyway V1 is the existing schema without psql directives; V2 adds booking snapshots,
guest credential hash, ticket/payment metadata and a durable event inbox. Do not run
Flyway against an existing manually initialized database without an audited baseline.

- `GET /api/bookings/my?status=ALL&page=0&size=20`: numeric JWT subject, verified
  issuer/audience through configured JWKS. Filters: ALL, UPCOMING, COMPLETED,
  CANCELLED. Response `{items,page,size,total}`. Bounds: page 0..100000, size 1..100.
  Stable descending createdAt/id ordering; UPCOMING uses Asia/Ho_Chi_Minh and last
  slot end time. Count/items use one repeatable-read transaction.
- `GET /api/bookings/{id}`: owner JWT or guest `X-Hold-Token` matching the stored
  SHA-256 digest for guest-only bookings. Other users/wrong tokens get 404.
  Never accept identity from unverified gateway headers. Response includes branch
  address/phone, court, date, `slots[{startTime,endTime,price}]`, totalPrice, status,
  paymentStatus, receiptImageUrl, checkInCode, qrImageUrl. Hash/user ID stay private.
  Missing legacy snapshots are nullable instead of invented data.

`AUTH_JWK_SET_URI`, `AUTH_JWT_ISSUER`, `AUTH_JWT_AUDIENCE` must match the auth
service. JWT subject is the numeric user ID, consistent with payment approval.
Only health and the credential-checked detail endpoint allow unauthenticated HTTP.

Kafka `fcourt.payment.events` uses the SCRUM-38 envelope
`{eventId,type,payment:{id,bookingId,branchId,amount,status,checkInCode,qrImageUrl,receiptImageUrl}}`.
The consumer locks the existing booking, checks branch/payment/amount identity,
updates payment/ticket metadata atomically with inbox deduplication, and prevents
late events from resurrecting cancelled bookings or regressing confirmed ones.
Kafka access must restrict producers of this financial topic. Unsupported event types
are ignored. Failed supported events roll back the inbox and retry indefinitely;
monitor blocked partitions and repair the source record/creation ordering explicitly.
`BOOKING_EVENTS_ENABLED=false` disables the listener for isolated local checks.

## Integration boundary

This task reads existing bookings and synchronizes payment events; it does not create
bookings. SCRUM-24's current hold API only creates a Redis hold and returns its token.
The booking-creation owner must atomically persist the booking, slots, branch/contact
snapshots, guest hash and original hold expiration before exposing a booking ID to
checkout. No public snapshot-write endpoint or cross-service database access is added.
Facility slot-release consumers and full checkout acceptance remain integration work.
Ticket PNG remains the fixed authenticated payment endpoint, not a public image URL.

## Validation

`JAVA_HOME=<Java 21> mvn -f booking-service/pom.xml clean verify` runs six tests
against Testcontainers PostgreSQL 16: two Flyway migrations, unauthorized/spoofed
identity, ownership/guest access, pagination/filter bounds, ticket-event persistence,
duplicate delivery, late-event ordering and amount-mismatch rollback.
The executable jar is built by verify. No production/full-flow acceptance is claimed.

## Cancellation — SCRUM-55

Owner JWT only: GET `/api/bookings/{id}/cancellation-policy` returns allowed/reason,
startsAt/cancelBefore, cutoffHours, refundAmount/refundPercent and currency VND.
POST `/api/bookings/{id}/cancel` with `{reason}` (1–500 trimmed characters) rechecks
ownership/state/deadline under a row lock. Eligible states are pending payment,
awaiting confirmation and confirmed; checked-in/completed bookings cannot cancel.
Exactly at the cutoff is allowed; requests after it return 400. Missing slot times
fail closed. `booking.cancel-cutoff-hours` defaults to 12; `booking.refund-percent`
defaults to 100, bounds 0..100. Values require product agreement before production.
Only confirmed payments produce a potential refund, rounded down to whole VND.
This records entitlement; it does not transfer money. Refund requests follow SCRUM-94.

Flyway V3 stores reason/cancel timestamp/refund amount and deactivates slot reservations
using a partial unique index, retaining all historical slots. Cancellation and the
outbox event commit atomically; repeated/concurrent cancellation emits one event.
Payment confirmation remains CONFIRMED when a paid booking is cancelled.

Outbox sends at-least-once to `fcourt.booking.events` keyed by booking ID:
`{eventId,type:"BookingCancelledEvent",booking:{id,bookingId,branchId,customerId,
bookingCode,status,reason,paymentId,paymentStatus,refundAmount,slots}}`.
Slots contain courtId/date/startTime/endTime. Consumers must deduplicate eventId.
Facility releases availability; payment opens refund eligibility. Consumer deployment
and actual slot-release/refund acceptance are pending with those owners. Kafka outage
keeps durable records unpublished; the relay retries without duplicating HTTP effects.
