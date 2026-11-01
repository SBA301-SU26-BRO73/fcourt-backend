package com.fcourt.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NotificationTopicsTest {
    @Test
    void spacesAroundCommasAreNotPartOfTheTopicName() {
        assertThat(new NotificationTopics(" fcourt.auth.events , fcourt.payment.events,fcourt.booking.events ").names())
                .containsExactly("fcourt.auth.events", "fcourt.payment.events", "fcourt.booking.events");
    }

    @Test
    void emptyEntriesAndDuplicatesAreDropped() {
        assertThat(new NotificationTopics("a,,b, a ,").names()).containsExactly("a", "b");
    }

    @Test
    void aListWithoutAnyTopicFailsAtStartup() {
        assertThatThrownBy(() -> new NotificationTopics(" , ")).isInstanceOf(IllegalStateException.class);
    }
}
