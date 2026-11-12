package com.fcourt.notification.inbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.notification.event.EventEnvelope;
import com.fcourt.notification.inapp.InAppEventHandler;
import com.fcourt.notification.mail.EmailDeliveryException;
import com.fcourt.notification.push.PushDeliveryService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class NotificationServiceTest {
    static final String PAYLOAD = """
            {"type":"PaymentConfirmedEvent","eventId":"6f6b96f3-ef66-45e9-bb7c-fd77714e60d0",
             "payment":{"bookingId":2936,"customerId":10}}""";

    final EmailEventHandler emails = mock(EmailEventHandler.class);
    final InAppEventHandler inApp = mock(InAppEventHandler.class);
    final PushDeliveryService pushes = mock(PushDeliveryService.class);
    final NotificationService service = new NotificationService(new ObjectMapper(), emails, inApp, pushes);

    @Test
    void pushFailureOccursAfterEmailTransactionReturnsSoKafkaRetryDoesNotResendMail() {
        doThrow(new IllegalStateException("SNS unavailable")).when(pushes).send(any());

        assertThatThrownBy(() -> service.handle(PAYLOAD)).hasMessage("SNS unavailable");

        InOrder order = inOrder(emails, inApp, pushes);
        order.verify(emails).handle(PAYLOAD);
        order.verify(inApp).handle(any(EventEnvelope.class));
        order.verify(pushes).send(any(EventEnvelope.class));
    }

    @Test
    void emailFailureStillCreatesInAppAndPushThenRethrowsForRetry() {
        when(emails.handle(PAYLOAD)).thenThrow(new EmailDeliveryException("smtp down", new RuntimeException()));

        assertThatThrownBy(() -> service.handle(PAYLOAD)).isInstanceOf(EmailDeliveryException.class);

        verify(inApp).handle(argThat(e -> "PaymentConfirmedEvent".equals(e.type())));
        verify(pushes).send(any(EventEnvelope.class));
    }

    @Test
    void emailProcessingCommitsInItsOwnTransactionalBean() throws Exception {
        var method = EmailEventHandler.class.getMethod("handle", String.class);
        org.assertj.core.api.Assertions.assertThat(method.isAnnotationPresent(
                org.springframework.transaction.annotation.Transactional.class)).isTrue();
    }
}
