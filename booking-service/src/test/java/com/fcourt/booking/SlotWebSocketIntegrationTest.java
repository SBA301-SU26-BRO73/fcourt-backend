package com.fcourt.booking;

import static org.assertj.core.api.Assertions.*;
import java.lang.reflect.Type;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.test.context.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"booking.events-enabled=false", "booking.realtime.enabled=true"})
class SlotWebSocketIntegrationTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static GenericContainer<?> redis=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void configure(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",db::getJdbcUrl);
        p.add("spring.datasource.username",db::getUsername);
        p.add("spring.datasource.password",db::getPassword);
        p.add("spring.data.redis.host",redis::getHost);
        p.add("spring.data.redis.port",()->redis.getMappedPort(6379));
    }
    @LocalServerPort int port;
    @Autowired StringRedisTemplate publisher;
    @Test void redisReachesTwoIndependentWebSocketClients() throws Exception {
        var client=new WebSocketStompClient(new StandardWebSocketClient());
        try {
            var first=client.connectAsync("ws://localhost:"+port+"/ws-fcourt",new StompSessionHandlerAdapter(){}).get(5,TimeUnit.SECONDS);
            var second=client.connectAsync("ws://localhost:"+port+"/ws-fcourt",new StompSessionHandlerAdapter(){}).get(5,TimeUnit.SECONDS);
            var a=new CompletableFuture<String>(); var b=new CompletableFuture<String>();
            first.subscribe("/topic/court-slots/21",handler(a));
            second.subscribe("/topic/court-slots/21",handler(b));
            for(int n=0;n<30 && (!a.isDone() || !b.isDone());n++) {
                publisher.convertAndSend(SlotChanges.CHANNEL,"{\"courtId\":21,\"date\":\"2030-01-02\",\"holdToken\":\"secret\"}");
                try {CompletableFuture.allOf(a,b).get(100,TimeUnit.MILLISECONDS);}catch(TimeoutException retry){}
            }
            for(var result:java.util.List.of(a,b)) assertThat(result.get(1,TimeUnit.SECONDS)).contains("2030-01-02").doesNotContain("secret","holdToken");
            first.disconnect(); second.disconnect();
        } finally {client.stop();}
    }
    private StompFrameHandler handler(CompletableFuture<String> result) {
        return new StompFrameHandler() {
            public Type getPayloadType(StompHeaders headers){return byte[].class;}
            public void handleFrame(StompHeaders headers,Object payload){result.complete(new String((byte[])payload,java.nio.charset.StandardCharsets.UTF_8));}
        };
    }
}
