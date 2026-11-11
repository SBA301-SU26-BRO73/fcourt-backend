package com.fcourt.facility.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.facility.api.LockDtos.LockResponse;
import com.fcourt.facility.api.LockDtos.LockType;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisLockScheduleEventsTest {
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final ObjectMapper json = new ObjectMapper();
    final RedisLockScheduleEvents events = new RedisLockScheduleEvents(redis, json,
            java.time.Clock.fixed(java.time.Instant.parse("2030-01-05T17:30:00Z"), java.time.ZoneId.of("Asia/Ho_Chi_Minh")));

    static LockResponse lock(long court, LockType type, LocalDateTime from, LocalDateTime to) {
        return new LockResponse(99, UUID.randomUUID(), 3, court, type, from, to, "VIP wedding of Mr X",
                "internal notes", "Private event", "Mr X", "0900000000", 7, OffsetDateTime.now());
    }

    List<JsonNode> published(int expected) throws Exception {
        var messages = ArgumentCaptor.forClass(String.class);
        verify(redis, times(expected)).convertAndSend(eq("fcourt:slot-changes"), messages.capture());
        var parsed = new ArrayList<JsonNode>();
        for (String message : messages.getAllValues()) parsed.add(json.readTree(message));
        return parsed;
    }

    @Test
    void publishesOneEventPerTouchedDateWithPublicFieldsOnly() throws Exception {
        events.created(List.of(lock(5, LockType.PRIVATE_EVENT,
                LocalDateTime.of(2030, 1, 5, 22, 0), LocalDateTime.of(2030, 1, 6, 2, 0))));

        var sent = published(2);
        assertThat(sent).extracting(e -> e.path("date").asText()).containsExactly("2030-01-05", "2030-01-06");
        for (JsonNode event : sent) {
            assertThat(event.size()).isEqualTo(4);
            assertThat(event.path("courtId").asLong()).isEqualTo(5);
            assertThat(event.path("type").asText()).isEqualTo("LOCK_CREATED");
            assertThat(event.path("lockType").asText()).isEqualTo("PRIVATE_EVENT");
            assertThat(event.toString()).doesNotContain("wedding", "notes", "0900000000", "Mr X");
        }
    }

    @Test
    void windowEndingAtMidnightDoesNotTouchTheNextDay() throws Exception {
        events.removed(List.of(lock(5, LockType.OUT_OF_SERVICE,
                LocalDateTime.of(2030, 1, 5, 20, 0), LocalDateTime.of(2030, 1, 6, 0, 0))));

        var sent = published(1);
        assertThat(sent.get(0).path("type").asText()).isEqualTo("LOCK_REMOVED");
        assertThat(sent.get(0).path("date").asText()).isEqualTo("2030-01-05");
    }

    @Test
    void identicalCourtAndDateAreAnnouncedOnce() throws Exception {
        var from = LocalDateTime.of(2030, 1, 5, 10, 0);
        var to = LocalDateTime.of(2030, 1, 5, 12, 0);
        events.created(List.of(lock(5, LockType.OUT_OF_SERVICE, from, to), lock(6, LockType.OUT_OF_SERVICE, from, to),
                lock(5, LockType.OUT_OF_SERVICE, from, to)));

        assertThat(published(2)).extracting(e -> e.path("courtId").asLong()).containsExactly(5L, 6L);
    }

    @Test
    void courtStatusChangeAnnouncesTheNext30VenueDaysWithoutLockFields() throws Exception {
        events.courtStatusChanged(5);

        var sent = published(30);
        // 17:30Z is already 2030-01-06 in Asia/Ho_Chi_Minh.
        assertThat(sent.get(0).path("date").asText()).isEqualTo("2030-01-06");
        assertThat(sent.get(29).path("date").asText()).isEqualTo("2030-02-04");
        for (JsonNode event : sent) {
            assertThat(event.size()).isEqualTo(3);
            assertThat(event.path("courtId").asLong()).isEqualTo(5);
            assertThat(event.path("type").asText()).isEqualTo("SLOT_CHANGED");
        }
    }

    @Test
    void redisOutageNeverFailsTheCommittedCommand() {
        doThrow(new RuntimeException("redis offline")).when(redis).convertAndSend(anyString(), anyString());

        assertThatCode(() -> events.created(List.of(lock(5, LockType.OUT_OF_SERVICE,
                LocalDateTime.of(2030, 1, 5, 10, 0), LocalDateTime.of(2030, 1, 5, 12, 0)))))
                .doesNotThrowAnyException();
    }
}
