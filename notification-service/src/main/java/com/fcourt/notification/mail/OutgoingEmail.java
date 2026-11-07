package com.fcourt.notification.mail;

import java.util.Map;

/**
 * One email to send: {@code template} names the pair {@code mail/<template>.html} and
 * {@code mail/<template>.txt}, and {@code model} fills their {@code {{placeholders}}}.
 */
public record OutgoingEmail(String to, String subject, String template, Map<String, String> model, String qrValue) {
    public OutgoingEmail(String to, String subject, String template, Map<String, String> model) {
        this(to, subject, template, model, null);
    }
}
