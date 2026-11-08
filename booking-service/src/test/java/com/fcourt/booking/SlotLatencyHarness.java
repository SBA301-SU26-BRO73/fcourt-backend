package com.fcourt.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * SCRUM-108 latency harness: two independent native STOMP clients subscribe to the same court topic;
 * each sample is published with a unique date and the harness measures publish call -> frame received
 * in both clients. Every sample runs in ONE JVM on loopback, so the numbers describe the backend
 * fan-out only. They are NOT the Android multi-device, mobile network or production acceptance figure.
 *
 * <p>Run: {@code mvn -pl . test -Dtest=SlotFanoutLatencyTest} (no Docker) or
 * {@code mvn test -Dtest=SlotRedisLatencyIntegrationTest} (Docker: real Redis hop). Results go to stdout
 * and {@code target/slot-latency-<label>.txt}; tune with {@code -Dslot.latency.samples=200}.
 */
final class SlotLatencyHarness {
    static final long COURT = 21;
    private static final Pattern DATE = Pattern.compile("\"date\":\"(\\d{4}-\\d{2}-\\d{2})\"");
    private static final LocalDate BASE = LocalDate.of(2031, 1, 1);

    private SlotLatencyHarness() { }

    record Result(String label, int samples, double p50, double p95, double max, double mean) {
        String line() {
            return String.format("%s: samples=%d clients=2 publish->receive p50=%.2fms p95=%.2fms max=%.2fms mean=%.2fms",
                    label, samples, p50, p95, max, mean);
        }
    }

    /** @param publisher publishes one invalidation for {@code COURT} on the given date. */
    static Result run(String label, int port, Consumer<LocalDate> publisher) throws Exception {
        int samples = Integer.getInteger("slot.latency.samples", 200);
        var client = new WebSocketStompClient(new StandardWebSocketClient());
        try {
            var url = "ws://localhost:" + port + "/ws-fcourt";
            var first = client.connectAsync(url, new StompSessionHandlerAdapter() { }).get(5, TimeUnit.SECONDS);
            var second = client.connectAsync(url, new StompSessionHandlerAdapter() { }).get(5, TimeUnit.SECONDS);

            var received = new ConcurrentHashMap<LocalDate, long[]>(); // date -> {firstNanos, secondNanos}
            var warm = new CompletableFuture[] {new CompletableFuture<Void>(), new CompletableFuture<Void>()};
            var done = new CountDownLatch(samples * 2);
            first.subscribe("/topic/court-slots/" + COURT, handler(0, received, warm, done));
            second.subscribe("/topic/court-slots/" + COURT, handler(1, received, warm, done));

            // Subscriptions are asynchronous: publish warm-up events (dates before BASE) until both clients saw one.
            for (int n = 0; n < 100 && !(warm[0].isDone() && warm[1].isDone()); n++) {
                publisher.accept(BASE.minusDays(1));
                Thread.sleep(50);
            }
            assertThat(warm[0].isDone() && warm[1].isDone()).as("both clients subscribed").isTrue();

            long[] sentAt = new long[samples];
            for (int i = 0; i < samples; i++) {
                sentAt[i] = System.nanoTime();
                publisher.accept(BASE.plusDays(i));
                Thread.sleep(10);
            }
            assertThat(done.await(15, TimeUnit.SECONDS)).as("every client received every sample").isTrue();

            List<Double> millis = new ArrayList<>();
            for (int i = 0; i < samples; i++) {
                long[] at = received.get(BASE.plusDays(i));
                for (long nanos : at) millis.add((nanos - sentAt[i]) / 1_000_000.0);
            }
            Collections.sort(millis);
            var result = new Result(label, samples, percentile(millis, 0.50), percentile(millis, 0.95),
                    millis.get(millis.size() - 1), millis.stream().mapToDouble(Double::doubleValue).average().orElse(0));
            System.out.println("SLOT-LATENCY " + result.line());
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target", "slot-latency-" + label + ".txt"), result.line() + System.lineSeparator());
            first.disconnect();
            second.disconnect();
            return result;
        } finally {
            client.stop();
        }
    }

    static double percentile(List<Double> sorted, double p) {
        return sorted.get((int) Math.min(sorted.size() - 1, Math.ceil(p * sorted.size()) - 1));
    }

    @SuppressWarnings("rawtypes")
    private static StompFrameHandler handler(int client, ConcurrentHashMap<LocalDate, long[]> received,
                                             CompletableFuture[] warm, CountDownLatch done) {
        return new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }

            @SuppressWarnings("unchecked")
            @Override public void handleFrame(StompHeaders headers, Object payload) {
                long now = System.nanoTime();
                var matcher = DATE.matcher(new String((byte[]) payload, StandardCharsets.UTF_8));
                if (!matcher.find()) return;
                LocalDate date = LocalDate.parse(matcher.group(1));
                if (date.isBefore(BASE)) {
                    warm[client].complete(null);
                    return;
                }
                if (received.computeIfAbsent(date, d -> new long[2])[client] == 0) {
                    received.get(date)[client] = now;
                    done.countDown();
                }
            }
        };
    }
}
