package com.fcourt.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.support.*;

class SlotRealtimeTest {
    final SimpMessagingTemplate broker=mock(SimpMessagingTemplate.class);
    final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final SlotChanges changes=new SlotChanges(new ObjectMapper(),broker,redis,jdbc);
    void message(String payload) {
        var message=mock(Message.class);
        when(message.getBody()).thenReturn(payload.getBytes(StandardCharsets.UTF_8));
        changes.onMessage(message,null);
    }
    @Test void onlyPublicFieldsLeaveBroker() {
        message("{\"courtId\":12,\"date\":\"2026-11-07\",\"holdToken\":\"secret\",\"phone\":\"private\"}");
        verify(broker).convertAndSend("/topic/court-slots/12",Map.of("courtId",12L,"date","2026-11-07","type","SLOT_CHANGED"));
    }
    @Test void typedEventsAreForwardedOnlyFromTheAllowList() {
        message("{\"courtId\":3,\"date\":\"2026-11-07\",\"type\":\"HOLD_EXPIRED\",\"holdToken\":\"secret\"}");
        verify(broker).convertAndSend("/topic/court-slots/3",Map.of("courtId",3L,"date","2026-11-07","type","HOLD_EXPIRED"));
        message("{\"courtId\":4,\"date\":\"2026-11-07\",\"type\":\"DROP TABLE\"}");
        verify(broker).convertAndSend("/topic/court-slots/4",Map.of("courtId",4L,"date","2026-11-07","type","SLOT_CHANGED"));
    }
    @Test void lockEventsCarryOnlyTheAllowListedLockType() {
        message("{\"courtId\":5,\"date\":\"2026-11-07\",\"type\":\"LOCK_CREATED\",\"lockType\":\"PRIVATE_EVENT\",\"reason\":\"VIP wedding\",\"contactPhone\":\"0900000000\"}");
        verify(broker).convertAndSend("/topic/court-slots/5",Map.of("courtId",5L,"date","2026-11-07","type","LOCK_CREATED","lockType","PRIVATE_EVENT"));
        message("{\"courtId\":6,\"date\":\"2026-11-07\",\"type\":\"LOCK_REMOVED\",\"lockType\":\"<script>\"}");
        verify(broker).convertAndSend("/topic/court-slots/6",Map.of("courtId",6L,"date","2026-11-07","type","LOCK_REMOVED"));
        message("{\"courtId\":7,\"date\":\"2026-11-07\",\"type\":\"HOLD_CREATED\",\"lockType\":\"OUT_OF_SERVICE\"}");
        verify(broker).convertAndSend("/topic/court-slots/7",Map.of("courtId",7L,"date","2026-11-07","type","HOLD_CREATED"));
    }
    @Test void malformedAndOversizedNotificationsAreIgnored() {
        for(String payload:new String[]{"{}","not json","{\"courtId\":-1,\"date\":\"2026-11-07\"}","{\"courtId\":1,\"date\":\"2026-02-30\"}"," ".repeat(1025)})message(payload);
        verifyNoInteractions(broker);
    }
    @Test void clientsCannotForgeMessagesOrSubscribeWildcards() {
        var guard=new SlotWebSocket.PublicSubscriptions();
        for(String topic:new String[]{"/topic/court-slots/*","/topic/private","/topic/court-slots/-1","/topic/court-slots/1/secret"}) {
            var h=StompHeaderAccessor.create(StompCommand.SUBSCRIBE);h.setDestination(topic);
            assertThatThrownBy(()->guard.preSend(MessageBuilder.createMessage(new byte[0],h.getMessageHeaders()),null)).isInstanceOf(MessageDeliveryException.class);
        }
        var h=StompHeaderAccessor.create(StompCommand.SEND);h.setDestination("/topic/court-slots/1");
        assertThatThrownBy(()->guard.preSend(MessageBuilder.createMessage(new byte[0],h.getMessageHeaders()),null)).isInstanceOf(MessageDeliveryException.class);
        var allowed=StompHeaderAccessor.create(StompCommand.SUBSCRIBE);allowed.setDestination("/topic/court-slots/12");
        assertThat(guard.preSend(MessageBuilder.createMessage(new byte[0],allowed.getMessageHeaders()),null)).isNotNull();
    }
    @Test void publicationWaitsForCommitAndSurvivesRedisFailure() {
        when(jdbc.queryForList(anyString(),eq(42L))).thenReturn(java.util.List.of(Map.of("court_id",12L,"booking_date",java.sql.Date.valueOf("2026-11-07"))));
        TransactionSynchronizationManager.initSynchronization();
        try {
            changes.afterCommit(42);
            verifyNoInteractions(redis);
            var callbacks=TransactionSynchronizationManager.getSynchronizations();
            assertThat(callbacks).hasSize(1);
            doThrow(new RuntimeException("offline")).when(redis).convertAndSend(anyString(),anyString());
            assertThatCode(()->callbacks.getFirst().afterCommit()).doesNotThrowAnyException();
        } finally {TransactionSynchronizationManager.clearSynchronization();}
    }
    @Test void rollbackNeverPublishes() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            changes.afterCommit(42);
            TransactionSynchronizationManager.getSynchronizations().getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verifyNoInteractions(redis,jdbc);
        } finally {TransactionSynchronizationManager.clearSynchronization();}
    }
}
