package com.fcourt.notification.mail;

import com.fcourt.notification.mail.TemplateRenderer.Format;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Sends multipart (plain text and HTML) mail through the configured SMTP server. */
@Component
class SmtpEmailSender implements EmailSender {
    private final JavaMailSender mailSender;
    private final TemplateRenderer renderer;
    private final InternetAddress from;

    SmtpEmailSender(JavaMailSender mailSender, TemplateRenderer renderer,
                    @Value("${notification.mail.from}") String from) {
        this.mailSender = mailSender;
        this.renderer = renderer;
        this.from = parseSender(from);
    }

    /**
     * Parsed once at startup: a malformed {@code NOTIFICATION_MAIL_FROM} stops the service instead of
     * failing every send and pushing every event to the DLT.
     */
    private static InternetAddress parseSender(String from) {
        try {
            var address = new InternetAddress(from, true);
            address.validate();
            return address;
        } catch (AddressException error) {
            throw new IllegalStateException("notification.mail.from is not a valid address", error);
        }
    }

    @Override
    public void send(OutgoingEmail email) {
        try {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            message.setFrom(from);
            helper.setTo(email.to());
            helper.setSubject(email.subject());
            helper.setText(renderer.render(email.template(), email.model(), Format.TEXT),
                    renderer.render(email.template(), email.model(), Format.HTML));
            mailSender.send(message);
        } catch (MessagingException | MailException error) {
            // The cause stays attached for diagnosis; it never contains the message body.
            throw new EmailDeliveryException("Email delivery failed (" + error.getClass().getSimpleName() + ")",
                    error);
        }
    }
}
