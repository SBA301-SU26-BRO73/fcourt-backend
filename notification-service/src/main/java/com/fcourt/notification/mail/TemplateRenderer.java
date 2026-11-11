package com.fcourt.notification.mail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Fills {@code {{name}}} placeholders in the classpath templates. HTML output escapes every value and
 * turns line breaks into {@code <br>}; plain text output keeps values as they are. A placeholder with
 * no value is a programming error and fails instead of sending a half-empty email.
 */
@Component
public class TemplateRenderer {
    public enum Format {
        HTML("html"), TEXT("txt");

        private final String extension;

        Format(String extension) {
            this.extension = extension;
        }
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z0-9_]+)}}");

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String render(String template, Map<String, String> model, Format format) {
        String source = cache.computeIfAbsent("mail/" + template + "." + format.extension, TemplateRenderer::load);
        Matcher matcher = PLACEHOLDER.matcher(source);
        var out = new StringBuilder();
        while (matcher.find()) {
            String value = model.get(matcher.group(1));
            if (value == null) {
                throw new IllegalStateException("Template " + template + " needs a value for " + matcher.group(1));
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(format == Format.HTML ? html(value) : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static String html(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;").replace("\r\n", "\n").replace("\n", "<br>");
    }

    private static String load(String path) {
        try (var stream = new ClassPathResource(path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException("Missing email template " + path, error);
        }
    }
}
