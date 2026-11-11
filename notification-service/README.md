# notification-service

Spring Boot 3.5 / Java 21 / JdbcTemplate / Flyway / Spring Kafka / JavaMail. Port **8091**, database
**notification_db**.

Scope: **SCRUM-42 / SCRUM-90**. Consumes Kafka events and sends Vietnamese emails (plain text and HTML): court owner
approved, password reset, booking confirmed and booking cancelled. It also keeps the **in-app notifications** shown by the
web bell (see [In-app notifications](#in-app-notifications)): REST `/api/notifications/**` plus `/devices` and
`/actuator/health`.

## Run locally

```bash
# Postgres with an empty notification_db database, Kafka and an SMTP server (for example Mailpit)
export NOTIFICATION_DATABASE_URL=jdbc:postgresql://localhost:5432/notification_db
export NOTIFICATION_DATABASE_PASSWORD=...        # no default on purpose
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export NOTIFICATION_SMTP_HOST=localhost NOTIFICATION_SMTP_PORT=1025
export NOTIFICATION_SMTP_AUTH=false NOTIFICATION_SMTP_STARTTLS=false
./mvnw spring-boot:run
```

| Variable | Default |
|---|---|
| `SERVER_PORT` | `8091` |
| `NOTIFICATION_DATABASE_URL` / `_USER` / `_PASSWORD` | `jdbc:postgresql://localhost:5432/notification_db` / `postgres` / (required) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `NOTIFICATION_SMTP_HOST` / `_PORT` / `_USER` / `_PASSWORD` | `localhost` / `587` / blank / blank |
| `NOTIFICATION_SMTP_AUTH` / `NOTIFICATION_SMTP_STARTTLS` | `true` / `true` |
| `NOTIFICATION_MAIL_FROM` | `FCOURT <no-reply@fcourt.example>` |
| `NOTIFICATION_RESET_PASSWORD_URL` | `https://fcourt.example/reset-password` (deep link or page that takes `?token=`) |
| `NOTIFICATION_TOPICS` | `fcourt.auth.events,fcourt.payment.events,fcourt.booking.events` |
| `NOTIFICATION_RETRY_ATTEMPTS` / `NOTIFICATION_RETRY_INTERVAL_MS` | `3` / `2000` |
| `NOTIFICATION_ENABLED` | `true` (`false` stops the listeners) |
| `NOTIFICATION_REMINDER_DELAY_MS` | `900000` (scan pending bookings every 15 minutes) |
| `FACILITY_URL` | `http://facility-service:8083` (branch owner lookup for in-app notifications) |
| `STAFF_URL` | `http://staff-operations-service:8090` (branch staff lookup) |
| `STAFF_DIRECTORY_TOKEN` | blank (secret `fcourt-internal-tokens`; blank means branch staff get no in-app notification) |

Tests: `./mvnw clean verify` (Docker required: Testcontainers Postgres and Kafka). Unit tests alone:
`./mvnw test '-Dtest=!*IntegrationTest'`.

The readiness check does not include the SMTP server, so a mail outage does not make the pod unready.

## Events and emails

| Topic | Event type | Email | Recipient comes from |
|---|---|---|---|
| `fcourt.auth.events` | `OwnerApprovedEvent` | court owner approved | `data.email`, `data.fullName` |
| `fcourt.auth.events` | `PasswordResetRequestedEvent` | password reset link | `data.email`, `data.fullName` |
| `fcourt.payment.events` | `PaymentConfirmedEvent` | booking confirmed, with ticket code | `recipient` |
| `fcourt.payment.events` | `PaymentRejectedEvent` | booking cancelled, payment rejected | `recipient` |
| `fcourt.payment.events` | `BookingCancelledEvent` (unpaid checkout expired) | booking cancelled | `recipient` |
| `fcourt.booking.events` | `BookingCancelledEvent` (customer cancelled) | booking cancelled, with refund amount | `recipient` |

Every other type (`PaymentSubmittedEvent`, `RefundCompletedEvent`, ...) is ignored.

### Producer contract

auth-service publishes the recipient (`OwnerApprovedEvent` gained `email` and `fullName` in SCRUM-40, and
`PasswordResetRequestedEvent` has them). Payment events for authenticated customer checkout now include
the recipient snapshot when checkout has a Bearer token; phone-only guest checkout has no email. Booking-service cancellation events still
do not include recipient. This service expects each booking event to
carry a top level object next to `payment` or `booking`:

```json
{"eventId":"...","type":"PaymentConfirmedEvent",
 "recipient":{"email":"customer@demo.com","fullName":"An Nguyen"},
 "payment":{"id":9,"bookingId":42,"branchId":3,"amount":200000,"checkInCode":"1234...",
            "checkout":{"bookingCode":"BK-0042","branchName":"...","courtName":"...","date":"2026-11-05",
                        "customerName":"An Nguyen","slots":[{"startTime":"18:00:00","endTime":"18:30:00"}]}}}
```

Events without `recipient` are **recorded as `SKIPPED_NO_RECIPIENT` and no email is sent** (a warning is
logged with the event id). Checkout chỉ có số điện thoại không nhận được email. Other
fields used when present: `payment.checkout.{bookingCode,branchName,courtName,date,slots[],customerName}`,
`payment.{amount,checkInCode,rejectionReason}`, `booking.{bookingCode,reason,refundAmount,slots[]}`. A
malformed value in a field that is used (date, time, expiry) is a poison record, see below.

### Booking reminder (SCRUM-90)

`PaymentConfirmedEvent` with a valid `recipient.email`, `checkout.date`, `checkout.slots[].startTime` and
`payment.checkInCode` creates a reminder in `booking_reminders`. A scheduled scan sends one email with
booking details, a map link and an inline check-in QR when the first slot is about two hours away
(up to 135 minutes before play; a delayed scan catches up until the slot starts). A rejected or cancelled booking removes its pending reminder. A cancellation tombstone
prevents an older confirmation replay from recreating it. SMTP failure rolls back `reminder_sent`, so the
next scan retries. The confirmation producer must supply the `recipient` object above before reminders
can run for authenticated checkout. Existing payments created before the recipient snapshot was added
remain without email and cannot be backfilled from a booking ID alone.

## In-app notifications

The web bell reads rows of `in_app_notifications`. They are written by the same Kafka consumer as the emails
(`NotificationService`: email transaction, then in-app transaction, then push), **addressed by user id** and
independent of email: no recipient address is needed and a failed or skipped email does not matter. A guest (no
account, no `customerId`) gets none.

### Who gets what

| Event (topic) | Recipient | Title / link | Dedupe key |
|---|---|---|---|
| `PaymentConfirmedEvent` (payment) | customer (`payment.customerId`) | Đặt sân thành công, `/my-bookings` | `booking-confirmed:<bookingId>` |
| `PaymentRejectedEvent` (payment) | customer | Thanh toán bị từ chối, `/my-bookings` | `booking-cancelled:<bookingId>` |
| `BookingCancelledEvent` (payment: unpaid checkout expired) | customer | Đơn đặt sân đã hết hạn, `/my-bookings` | `booking-cancelled:<bookingId>` |
| `BookingCancelledEvent` (booking, `cancelledByRole` = `CUSTOMER`, `ADMIN`, `SYSTEM` or absent) | customer (`booking.customerId`), wording by role | Đã hủy đặt sân / Chủ sân đã hủy đơn / hết hạn, `/my-bookings` | `booking-cancelled:<bookingId>` |
| same, only when `cancelledByRole` = `CUSTOMER` | branch owner and branch staff | Khách đã hủy đặt sân, `/admin/bookings` (owner) or `/staff/board` (staff) | `booking-cancelled:<bookingId>` |
| `RefundCompletedEvent` (payment) | customer (`refund.customerId`) | Đã hoàn tiền, `/my-bookings` | `refund-completed:<refundId>` |
| `PaymentSubmittedEvent` (payment) | branch owner | Biên lai chờ duyệt, `/admin/bookings` | `payment-submitted:<eventId>` |
| `RefundRequestedEvent` (payment, new) | branch owner | Yêu cầu hoàn tiền mới, `/admin/refund-requests` | `refund-requested:<refundId>` |
| `OwnerApprovedEvent` (auth) | the owner (`data.userId`) | Hồ sơ chủ sân đã được duyệt, `/admin/dashboard` | `owner-approved:<userId>:<approvedAt>` (same as the email) |
| `OwnerRegisteredEvent` (auth, new) | every active super admin (`data.superAdminIds`) | Chủ sân mới chờ duyệt, `/super-admin/pending` | `owner-registered:<userId>` |

Why some recipients are what they are:

- Payment approval and refund decisions are ADMIN-only endpoints, so receipts and refund requests go to the owner
  only. Staff are told about customer cancellations (they run the schedule).
- Only an explicit `cancelledByRole = CUSTOMER` notifies the owner and staff. Events published before that field
  existed, owner cancellations, package cancellations and hold expiry do not.
- A rejected court owner can never sign in (login is refused), so there is no in-app notification for rejection.

### Producer contract (additions, all backward compatible)

- booking-service `BookingCancelledEvent.booking` gained `cancelledByRole` (`CUSTOMER` for the owner's own and the
  guest cancellation, `ADMIN` for the branch owner, `SYSTEM` for hold expiry; absent for full-court package
  cancellation).
- payment-service publishes `RefundRequestedEvent` on `fcourt.payment.events` when a refund request is created
  (not for a repeated identical request): `{"eventId","type","refund":{"id","bookingId","branchId","customerId","amount","status"}}`.
- auth-service publishes `OwnerRegisteredEvent` on `fcourt.auth.events` when a court owner registers:
  `data: {userId, email, fullName, registeredAt, superAdminIds[]}`, the active super admins at that moment.

Other consumers of these topics ignore types they do not know.

### Resolving the branch owner and staff

Events carry `branchId`, not the owner. `HttpBranchDirectory` asks the services that own the data (in cluster,
not routed by the gateway):

- Owner: `GET facility-service /api/internal/branches/{id}/owner` returns `{branchId, ownerId}` (open like the other
  internal GET routes of facility-service). An outage throws, so Kafka retries the event; an unknown branch (404)
  just skips the owner.
- Staff: `GET staff-operations-service /api/internal/branches/{id}/staff-user-ids` returns `{branchId, userIds[]}`
  for active staff, with header `X-Internal-Service-Token` = `STAFF_DIRECTORY_TOKEN` (503 when the service has no token,
  403 for a wrong one). Best effort: no token, an error or an outage means the staff are skipped and the owner is
  still notified.

### Idempotency

`UNIQUE (user_id, dedupe_key)` with `INSERT ... ON CONFLICT DO NOTHING`: a redelivered or duplicated event adds
nothing, and the same fact reported by two services (for example payment expiry and customer cancellation) is one
row per user. The keys are the email business keys (`BusinessKeys`), so both channels agree on what "the same
notification" is. An in-app failure (for example facility-service down) is retried by the Kafka error handler and
never resends an already committed email.

### REST API

JWT required (through the gateway `/api/notifications/**`). The user is the token subject; a user only ever sees or
changes their own rows (someone else's id answers 404).

| Method and path | Result |
|---|---|
| `GET /api/notifications/me?page=0&size=10` | `{items:[{id,type,title,body,link,readAt,createdAt}], page, size, total}`, newest first (size 1..50) |
| `GET /api/notifications/me/unread-count` | `{count}` |
| `PATCH /api/notifications/{id}/read` | 204, or 404 when it is not yours |
| `PATCH /api/notifications/me/read-all` | `{updated}` |

## Reliability

- **Idempotency.** Each handled event is recorded in `processed_events` by its `eventId`, so a redelivered
  event never sends a second email. `business_key` additionally dedupes different events about the same
  fact: `booking-confirmed:<bookingId>`, `booking-cancelled:<bookingId>` (customer cancellation, payment
  expiry and payment rejection share it) and `owner-approved:<userId>:<approvedAt>` (each approval is its
  own fact, so an owner approved again is mailed again; without `approvedAt` the `eventId` alone dedupes).
  Password reset has no business key, every request is mailed. A `SKIPPED_NO_RECIPIENT` row is recorded by
  `eventId` only and never takes the business key, so a later event about the same booking that does carry a
  recipient is still mailed. No address is stored in the table.
- **At-least-once.** The claim and the SMTP send share one database transaction. If the mail server refuses
  the message, the claim rolls back and the retry sends again. If the mail was accepted but the commit then
  fails, the recipient can get it twice.
- **Retry and dead letter.** A failing record is retried `NOTIFICATION_RETRY_ATTEMPTS` times,
  `NOTIFICATION_RETRY_INTERVAL_MS` apart (this blocks that partition meanwhile), then published to
  `<topic>.DLT` and skipped, so one bad record never blocks the topic. Unreadable records (invalid JSON,
  missing `eventId` or `type`, malformed date or time, reset event without token) are poison: no retries,
  straight to the DLT. Nothing replays the DLT automatically, someone has to look at it. DLT records keep
  the original payload except every `"token"` value, which is replaced by `[REDACTED]` (a dead lettered
  reset is not replayable; the user requests a new link). Names and addresses remain, so restrict access to
  those topics.
- **Logging.** Logs hold the event id, type and outcome only, never the recipient, the body or a reset token.
- A new deployment starts from the earliest offset of its consumer group, so it mails events published
  before it was running (an old `OwnerApprovedEvent` without `email` is recorded as skipped). A
  `PasswordResetRequestedEvent` whose `expiresAt` has already passed when it is consumed is not mailed (the
  link would no longer work); this is logged with the event id and not recorded.
- **Addresses.** A recipient is used only if it is one bare address that JavaMail also accepts; anything else
  counts as no recipient instead of failing every retry. `NOTIFICATION_MAIL_FROM` is checked at startup.

## Database (Flyway)

- `V1`: `processed_events`. The database must be empty on first start (the `docker/postgres` init script
  only creates it, because `notification-service/database` has no scripts).
- `V2`: `booking_reminders` and `cancelled_booking_reminders` projection tables.
- `V3`, `V4`: `user_devices` and push delivery / reminder tables.
- `V5`: `in_app_notifications` (`id`, `user_id`, `type`, `title`, `body`, `link`, `dedupe_key`, `read_at`, `created_at`),
  `UNIQUE (user_id, dedupe_key)`, indexes `(user_id, created_at DESC, id DESC)` and a partial one on unread rows.

## Templates

`src/main/resources/mail/<name>.html` and `<name>.txt`, with `{{placeholders}}`. HTML values are escaped and
line breaks become `<br>`. A placeholder without a value fails the send instead of mailing a half-empty
message.
