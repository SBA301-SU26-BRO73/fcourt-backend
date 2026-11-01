package com.fcourt.notification.kafka;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The consumed topics, parsed once from the comma separated {@code notification.topics}. The listener and
 * the dead letter topics both use this list, so {@code "a, b"} subscribes to {@code b} (not {@code " b"})
 * and every consumed topic gets its {@code .DLT}.
 */
@Component("notificationTopics")
public class NotificationTopics {
    private final List<String> names;

    NotificationTopics(@Value("${notification.topics}") String topics) {
        this.names = parse(topics);
    }

    public List<String> names() {
        return names;
    }

    static List<String> parse(String topics) {
        List<String> names = Arrays.stream(topics.split(","))
                .map(String::strip)
                .filter(name -> !name.isEmpty())
                .distinct()
                .toList();
        if (names.isEmpty()) {
            throw new IllegalStateException("notification.topics names no topic");
        }
        return names;
    }
}
