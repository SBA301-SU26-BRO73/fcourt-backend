package com.fcourt.notification.inbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fcourt.notification.event.EventEnvelope;
import com.fcourt.notification.inapp.InAppEventHandler;
import com.fcourt.notification.push.PushDeliveryService;
import org.junit.jupiter.api.Test;

class NotificationServiceTest {
    @Test
    void pushFailureOccursAfterEmailTransactionReturnsSoKafkaRetryDoesNotResendMail() {
        EmailEventHandler emails = mock(EmailEventHandler.class);
        PushDeliveryService pushes = mock(PushDeliveryService.class);
        EventEnvelope event = mock(EventEnvelope.class);
        when(emails.handle("payload")).thenReturn(event);
        doThrow(new IllegalStateException("SNS unavailable")).when(pushes).send(event);
        InAppEventHandler inApp = mock(InAppEventHandler.class);
        NotificationService service = new NotificationService(emails, inApp, pushes);

        assertThatThrownBy(() -> service.handle("payload")).hasMessage("SNS unavailable");

        verify(emails).handle("payload");
        verify(pushes).send(event);
    }

    @Test
    void emailProcessingCommitsInItsOwnTransactionalBean() throws Exception {
        var method = EmailEventHandler.class.getMethod("handle", String.class);
        org.assertj.core.api.Assertions.assertThat(method.isAnnotationPresent(
                org.springframework.transaction.annotation.Transactional.class)).isTrue();
    }
}
