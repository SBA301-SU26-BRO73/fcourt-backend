package com.fcourt.notification.reminder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.notification.push.PushDeliveryService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import static org.assertj.core.api.Assertions.assertThat;

class ReminderWorkerTest {
    @Test void pollsFakeSqsOnItsOwnThreadAndStopsGracefully() throws Exception {
        SqsClient sqs=mock(SqsClient.class);
        CountDownLatch polled=new CountDownLatch(1);
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenAnswer(call -> {
            polled.countDown();
            return ReceiveMessageResponse.builder().build();
        });
        ReminderWorker worker=new ReminderWorker(sqs, mock(JdbcTemplate.class), mock(PushDeliveryService.class),
                new ObjectMapper(), "queue-url", 120, mock(PlatformTransactionManager.class));
        worker.start();
        assertThat(polled.await(3, TimeUnit.SECONDS)).isTrue();
        worker.stop();
        assertThat(worker.isRunning()).isFalse();
        verify(sqs, atLeastOnce()).receiveMessage(any(ReceiveMessageRequest.class));
    }
}
