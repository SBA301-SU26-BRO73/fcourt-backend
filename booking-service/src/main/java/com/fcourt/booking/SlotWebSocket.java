package com.fcourt.booking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.config.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
@ConditionalOnProperty(name="booking.realtime.enabled", havingValue="true")
class SlotWebSocket implements WebSocketMessageBrokerConfigurer {
    private final String[] origins;
    SlotWebSocket(@Value("${booking.realtime.allowed-origins:http://localhost:3000}") String[] origins) {this.origins=origins;}
    @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-fcourt").setAllowedOrigins(origins);
    }
    @Override public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic/court-slots");
        registry.setApplicationDestinationPrefixes("/app");
    }
    @Override public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(4096).setSendBufferSizeLimit(16384).setSendTimeLimit(10000).setTimeToFirstMessage(10000);
    }
    @Override public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new PublicSubscriptions());
    }
    static class PublicSubscriptions implements ChannelInterceptor {
        @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
            var h=MessageHeaderAccessor.getAccessor(message,StompHeaderAccessor.class);
            if(h==null || h.getCommand()==null) return message;
            if(h.getCommand()==StompCommand.SEND ||
                (h.getCommand()==StompCommand.SUBSCRIBE && (h.getDestination()==null || !h.getDestination().matches("/topic/court-slots/[1-9][0-9]{0,17}"))))
                throw new MessageDeliveryException("Only public court slot subscriptions are allowed");
            return message;
        }
    }
    @Bean RedisMessageListenerContainer slotNotifications(RedisConnectionFactory connection, SlotChanges changes) {
        var container=new RedisMessageListenerContainer();
        container.setConnectionFactory(connection);
        container.addMessageListener(changes,new ChannelTopic(SlotChanges.CHANNEL));
        return container;
    }
}
