package com.fcourt.notification.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.notification.event.EmailPlan;
import com.fcourt.notification.event.EmailPlanner;
import com.fcourt.notification.event.EventEnvelope;
import com.fcourt.notification.event.PoisonEventException;
import com.fcourt.notification.inbox.ProcessedEventRepository.Outcome;
import com.fcourt.notification.mail.EmailDeliveryException;
import com.fcourt.notification.mail.EmailSender;
import com.fcourt.notification.mail.OutgoingEmail;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class NotificationServiceTest {
    static final UUID EVENT_ID = UUID.fromString("b6c9aabf-2f7e-4b50-9d2e-0b1d6f0b9a11");
    static final String PAYLOAD = "{\"eventId\":\"" + EVENT_ID + "\",\"type\":\"OwnerApprovedEvent\"}";
    static final OutgoingEmail EMAIL = new OutgoingEmail("owner@demo.com", "subject", "owner-approved",
            Map.of("name", "Chu San"));

    EmailPlanner planner;
    ProcessedEventRepository processed;
    EmailSender sender;
    NotificationService service;

    @BeforeEach
    void setUp() {
        planner = mock(EmailPlanner.class);
        processed = mock(ProcessedEventRepository.class);
        sender = mock(EmailSender.class);
        service = new NotificationService(new ObjectMapper(), planner, processed, sender,
                org.mockito.Mockito.mock(com.fcourt.notification.reminder.ReminderProjection.class));
    }

    @Test
    void theFirstDeliveryClaimsTheEventAndSendsTheEmailOnce() {
        when(planner.plan(any())).thenReturn(new EmailPlan.Send(EMAIL, "owner-approved:17"));
        when(processed.claim(EVENT_ID, "OwnerApprovedEvent", "owner-approved:17", Outcome.SENT)).thenReturn(true);

        service.handle(PAYLOAD);

        verify(sender).send(EMAIL);
    }

    @Test
    void aRedeliveredOrDuplicateEventSendsNothing() {
        when(planner.plan(any())).thenReturn(new EmailPlan.Send(EMAIL, "owner-approved:17"));
        when(processed.claim(eq(EVENT_ID), anyString(), anyString(), eq(Outcome.SENT))).thenReturn(false);

        service.handle(PAYLOAD);

        verify(sender, never()).send(any());
    }

    @Test
    void anEventWithoutARecipientIsRecordedAsSkippedWithoutUsingUpTheBusinessKey() {
        when(planner.plan(any())).thenReturn(new EmailPlan.NoRecipient());
        when(processed.claim(EVENT_ID, "OwnerApprovedEvent", null, Outcome.SKIPPED_NO_RECIPIENT)).thenReturn(true);

        service.handle(PAYLOAD);

        verify(processed).claim(EVENT_ID, "OwnerApprovedEvent", null, Outcome.SKIPPED_NO_RECIPIENT);
        verifyNoInteractions(sender);
    }

    @Test
    void eventsThisServiceDoesNotMailAreNotRecordedAtAll() {
        when(planner.plan(any())).thenReturn(new EmailPlan.Ignore());

        service.handle(PAYLOAD);

        verifyNoInteractions(processed, sender);
    }

    @Test
    void anExpiredEventSendsNothingAndIsNotRecorded() {
        when(planner.plan(any())).thenReturn(new EmailPlan.Expired());

        service.handle(PAYLOAD);

        verifyNoInteractions(processed, sender);
    }

    @Test
    void aMailServerFailurePropagatesSoTheClaimRollsBackAndKafkaRetries() {
        when(planner.plan(any())).thenReturn(new EmailPlan.Send(EMAIL, null));
        when(processed.claim(any(), anyString(), any(), any())).thenReturn(true);
        doThrow(new EmailDeliveryException("Email delivery failed (MailSendException)", null))
                .when(sender).send(any());

        assertThatThrownBy(() -> service.handle(PAYLOAD)).isInstanceOf(EmailDeliveryException.class);
    }

    @Test
    void theClaimAndTheSendShareOneTransaction() throws NoSuchMethodException {
        assertThat(NotificationService.class.getMethod("handle", String.class).getAnnotation(Transactional.class))
                .isNotNull();
    }

    @Test
    void unreadablePayloadsAreRejectedBeforeAnythingElseHappens() {
        for (String payload : new String[] {null, "", "{not json", "{\"eventId\":\"" + EVENT_ID + "\"}",
                "{\"type\":\"OwnerApprovedEvent\"}", "{\"eventId\":\"nope\",\"type\":\"OwnerApprovedEvent\"}"}) {
            assertThatThrownBy(() -> service.handle(payload)).isInstanceOf(PoisonEventException.class);
        }

        verifyNoInteractions(planner, processed, sender);
    }

    @Test
    void theEnvelopeCarriesTheProducersEventId() {
        var envelope = EventEnvelope.parse(new ObjectMapper(), PAYLOAD);

        assertThat(envelope.eventId()).isEqualTo(EVENT_ID);
        assertThat(envelope.type()).isEqualTo("OwnerApprovedEvent");
    }
}
