# Push delivery and booking reminders

Kafka push delivery is deduplicated per logical event and device. Booking cancellation and payment expiry/rejection use the same stable cancellation key, matching the email business key, so the payment and booking topics do not send duplicate cancellation pushes. Each device delivery commits independently: a temporary SNS error retries the event without resending to devices already marked sent. The email handler commits its inbox/email transaction before push delivery starts, so SNS retries do not resend mail.

Push reminders are opt-in with PUSH_REMINDER_ENABLED=true. EventBridge Scheduler sends a one-time message two hours before the projected booking start to PUSH_REMINDER_QUEUE_URL; set PUSH_REMINDER_QUEUE_ARN, PUSH_REMINDER_ROLE_ARN, PUSH_REMINDER_SCHEDULER_GROUP, and PUSH_AWS_REGION for the AWS resources. Configure the queue redrive policy and DLQ in infrastructure. The worker rechecks both reminder projection tables, uses a separate push_reminder_sent flag, and only then sends push. The existing ReminderScheduler and its email reminder behavior remain enabled and use the separate reminder_sent flag.

The schedule and worker depend on booking_reminders, which is projected from confirmed payment events. It can retain a row without an email recipient for push reminders; the email scheduler filters those rows out.
