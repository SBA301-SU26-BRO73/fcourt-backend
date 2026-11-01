package com.fcourt.notification.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class SmtpEmailSenderTest {
    static final String FROM = "FCOURT <no-reply@fcourt.example>";

    List<MimeMessage> sent;
    boolean fail;
    SmtpEmailSender sender;

    @BeforeEach
    void setUp() {
        sent = new ArrayList<>();
        fail = false;
        var transport = new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage message) {
                if (fail) {
                    throw new MailSendException("smtp down");
                }
                sent.add(message);
            }
        };
        sender = new SmtpEmailSender(transport, new TemplateRenderer(), FROM);
    }

    @Test
    void sendsOneMessageWithSubjectSenderRecipientAndBothBodies() throws Exception {
        sender.send(new OutgoingEmail("owner@demo.com", "Hồ sơ chủ sân FCOURT đã được duyệt", "owner-approved",
                Map.of("name", "Chu San")));

        assertThat(sent).hasSize(1);
        var message = sent.getFirst();
        message.saveChanges();
        assertThat(message.getSubject()).isEqualTo("Hồ sơ chủ sân FCOURT đã được duyệt");
        assertThat(message.getFrom()[0]).hasToString(FROM);
        assertThat(message.getAllRecipients()[0]).hasToString("owner@demo.com");
        var bodies = new ArrayList<String>();
        collect(message, bodies);
        assertThat(bodies).anyMatch(body -> body.contains("Xin chào Chu San,") && !body.contains("<html"));
        assertThat(bodies).anyMatch(body -> body.contains("<html lang=\"vi\">") && body.contains("Chu San"));
    }

    @Test
    void htmlBodyEscapesTheRecipientName() throws Exception {
        sender.send(new OutgoingEmail("owner@demo.com", "s", "owner-approved", Map.of("name", "<b>x</b>")));

        var bodies = new ArrayList<String>();
        var message = sent.getFirst();
        message.saveChanges();
        collect(message, bodies);
        assertThat(bodies).anyMatch(body -> body.contains("&lt;b&gt;x&lt;/b&gt;"));
    }

    @Test
    void aMailServerFailureBecomesADeliveryExceptionThatKeepsNoBody() {
        fail = true;

        assertThatThrownBy(() -> sender.send(new OutgoingEmail("owner@demo.com", "s", "owner-approved",
                Map.of("name", "Chu San"))))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessage("Email delivery failed (MailSendException)")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("Chu San", "owner@demo.com"));
    }

    private static void collect(Part part, List<String> bodies) throws Exception {
        if (part.isMimeType("text/plain") || part.isMimeType("text/html")) {
            bodies.add((String) part.getContent());
        } else if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                collect(child, bodies);
            }
        }
    }
}
