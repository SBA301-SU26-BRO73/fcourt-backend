package com.fcourt.notification.inapp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fcourt.notification.event.EventEnvelope;
import java.util.List;
import org.junit.jupiter.api.Test;

class InAppEventHandlerTest {
    InAppPlanner planner = mock(InAppPlanner.class);
    InAppNotificationRepository repository = mock(InAppNotificationRepository.class);
    InAppEventHandler handler = new InAppEventHandler(planner, repository);

    @Test
    void insertsEveryPlannedRowIncludingTheOnesAnEarlierDeliveryAlreadyAdded() {
        EventEnvelope event = mock(EventEnvelope.class);
        var first = new InAppPlan(1, "T", "a", "b", "/x", "k1");
        var second = new InAppPlan(2, "T", "a", "b", "/x", "k1");
        when(planner.plan(event)).thenReturn(List.of(first, second));
        when(repository.insert(first)).thenReturn(false); // replay: the unique key keeps it from duplicating
        when(repository.insert(second)).thenReturn(true);

        handler.handle(event);

        verify(repository).insert(first);
        verify(repository).insert(second);
    }

    @Test
    void writesNothingWhenThereIsNoRecipient() {
        EventEnvelope event = mock(EventEnvelope.class);
        when(planner.plan(event)).thenReturn(List.of());

        handler.handle(event);

        verify(repository, never()).insert(any());
    }

    @Test
    void runsInOneTransaction() throws Exception {
        var method = InAppEventHandler.class.getMethod("handle", EventEnvelope.class);
        org.assertj.core.api.Assertions.assertThat(method.isAnnotationPresent(
                org.springframework.transaction.annotation.Transactional.class)).isTrue();
    }
}
