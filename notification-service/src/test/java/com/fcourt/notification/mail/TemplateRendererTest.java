package com.fcourt.notification.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fcourt.notification.mail.TemplateRenderer.Format;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TemplateRendererTest {
    TemplateRenderer renderer = new TemplateRenderer();

    @Test
    void textOutputKeepsValuesAsTheyAre() {
        String text = renderer.render("owner-approved", Map.of("name", "Chu <San> & Co"), Format.TEXT);

        assertThat(text).contains("Xin chào Chu <San> & Co,").doesNotContain("{{");
    }

    @Test
    void htmlOutputEscapesEveryValueSoMarkupInDataCannotInjectContent() {
        String html = renderer.render("owner-approved", Map.of("name", "<script>alert('x')</script>"), Format.HTML);

        assertThat(html).contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;").doesNotContain("<script>");
    }

    @Test
    void htmlOutputTurnsLineBreaksIntoBreaks() {
        String html = renderer.render("booking-confirmed", Map.of("name", "An", "bookingCode", "BK1",
                "details", "Sân: 1\nNgày: 01/11/2026", "amount", "100.000 ₫", "ticketCode", "123"), Format.HTML);

        assertThat(html).contains("Sân: 1<br>Ngày: 01/11/2026");
    }

    @Test
    void quotesInValuesCannotBreakOutOfTheLinkAttribute() {
        String html = renderer.render("password-reset", Map.of("name", "An", "expiresAt", "17:30 01/11/2026",
                "resetLink", "https://x/r?token=a\"onmouseover=\"alert(1)"), Format.HTML);

        assertThat(html).contains("href=\"https://x/r?token=a&quot;onmouseover=&quot;alert(1)\"");
    }

    @Test
    void dollarSignsAndBackslashesInValuesAreNotTreatedAsRegexReplacements() {
        String text = renderer.render("owner-approved", Map.of("name", "A$1\\B"), Format.TEXT);

        assertThat(text).contains("Xin chào A$1\\B,");
    }

    @Test
    void aMissingValueFailsInsteadOfSendingAHalfEmptyEmail() {
        assertThatThrownBy(() -> renderer.render("owner-approved", Map.of(), Format.TEXT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("name");
    }

    @Test
    void anUnknownTemplateFails() {
        assertThatThrownBy(() -> renderer.render("no-such-template-" + UUID.randomUUID(), Map.of(), Format.TEXT))
                .isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner-approved", "password-reset", "booking-confirmed", "booking-cancelled"})
    void everyTemplateExistsAsPlainTextAndHtmlInVietnamese(String template) {
        var model = Map.of("name", "An", "expiresAt", "x", "resetLink", "https://x", "bookingCode", "BK1",
                "details", "d", "amount", "a", "ticketCode", "t", "reason", "r", "note", "n");

        assertThat(renderer.render(template, model, Format.TEXT)).contains("Xin chào An");
        assertThat(renderer.render(template, model, Format.HTML)).contains("<html lang=\"vi\">", "Xin chào An");
    }
}
