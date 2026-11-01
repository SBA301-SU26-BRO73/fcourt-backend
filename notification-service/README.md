# notification-service

Spring Boot 3.5 / Java 21 / JdbcTemplate / Flyway / Spring Kafka / JavaMail. Port **8091**, database
**notification_db**.

Scope: **SCRUM-42**. Consumes Kafka events and sends Vietnamese emails (plain text and HTML): court owner
approved, password reset, booking confirmed and booking cancelled. There is no REST API besides
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

### Producer contract that does not exist yet

auth-service publishes the recipient (`OwnerApprovedEvent` gained `email` and `fullName` in SCRUM-40, and
`PasswordResetRequestedEvent` has them). **payment-service and booking-service do not publish any
recipient today**: their events carry ids, amounts and (payment) the checkout, but no customer email, and
guest bookings only have a phone number. This service therefore expects each of the three booking events to
carry a top level object next to `payment` or `booking`:

```json
{"eventId":"...","type":"PaymentConfirmedEvent",
 "recipient":{"email":"customer@demo.com","fullName":"An Nguyen"},
 "payment":{"id":9,"bookingId":42,"branchId":3,"amount":200000,"checkInCode":"1234...",
            "checkout":{"bookingCode":"BK-0042","branchName":"...","courtName":"...","date":"2026-11-05",
                        "customerName":"An Nguyen","slots":[{"startTime":"18:00:00","endTime":"18:30:00"}]}}}
```

Until the producers add `recipient`, these events are **recorded as `SKIPPED_NO_RECIPIENT` and no email is
sent** (a warning is logged with the event id). Guest customers (phone only) never get an email. Other
fields used when present: `payment.checkout.{bookingCode,branchName,courtName,date,slots[],customerName}`,
`payment.{amount,checkInCode,rejectionReason}`, `booking.{bookingCode,reason,refundAmount,slots[]}`. A
malformed value in a field that is used (date, time, expiry) is a poison record, see below.

## Reliability

- **Idempotency.** Each handled event is recorded in `processed_events` by its `eventId`, so a redelivered
  event never sends a second email. `business_key` additionally dedupes different events about the same
  fact: `booking-confirmed:<bookingId>`, `booking-cancelled:<bookingId>` (customer cancellation, payment
  expiry and payment rejection share it) and `owner-approved:<userId>`. Password reset has no business key,
  every request is mailed. No address is stored in the table.
- **At-least-once.** The claim and the SMTP send share one database transaction. If the mail server refuses
  the message, the claim rolls back and the retry sends again. If the mail was accepted but the commit then
  fails, the recipient can get it twice.
- **Retry and dead letter.** A failing record is retried `NOTIFICATION_RETRY_ATTEMPTS` times,
  `NOTIFICATION_RETRY_INTERVAL_MS` apart (this blocks that partition meanwhile), then published to
  `<topic>.DLT` and skipped, so one bad record never blocks the topic. Unreadable records (invalid JSON,
  missing `eventId` or `type`, malformed date or time, reset event without token) are poison: no retries,
  straight to the DLT. Nothing replays the DLT automatically, someone has to look at it. DLT records keep
  the original payload, including password reset tokens, so restrict access to those topics.
- **Logging.** Logs hold the event id, type and outcome only, never the recipient, the body or a reset token.
- A new deployment starts from the earliest offset of its consumer group, so it mails events published
  before it was running (an old `OwnerApprovedEvent` without `email` is recorded as skipped).

## Database (Flyway)

- `V1`: `processed_events`. The database must be empty on first start (the `docker/postgres` init script
  only creates it, because `notification-service/database` has no scripts).

## Templates

`src/main/resources/mail/<name>.html` and `<name>.txt`, with `{{placeholders}}`. HTML values are escaped and
line breaks become `<br>`. A placeholder without a value fails the send instead of mailing a half-empty
message.
