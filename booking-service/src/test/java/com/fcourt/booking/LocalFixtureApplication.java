package com.fcourt.booking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.function.*;

/** Only the business clock is controlled; JWT validation uses the actual system clock. */
public class LocalFixtureApplication {
  public static void main(String[] args) {
    if (!"true".equals(System.getenv("FCOURT_LOCAL_TEST")))
      throw new IllegalStateException("Local fixture opt-in required");
    SpringApplication.run(new Class<?>[] {BookingApplication.class, Fixture.class}, args);
  }

  @TestConfiguration
  static class Fixture {
    private final AtomicReference<Instant> now = new AtomicReference<>();

    @Bean
    @Primary
    Clock fixtureClock() {
      return new Clock() {
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
          return Clock.fixed(instant(), zone);
        }

        public Instant instant() {
          var value = now.get();
          return value == null ? Instant.now() : value;
        }
      };
    }

    @Bean
    @Order(0)
    SecurityFilterChain fixtureSecurity(HttpSecurity http) throws Exception {
      return http.securityMatcher("/__local_test__/clock")
          .csrf(c -> c.disable())
          .authorizeHttpRequests(a -> a.anyRequest().permitAll())
          .build();
    }

    @Bean
    RouterFunction<ServerResponse> fixtureClockRoute() {
      String expected = System.getenv("FCOURT_FIXTURE_CONTROL_KEY");
      if (expected == null || expected.length() < 32)
        throw new IllegalStateException("Fixture control key required");
      return RouterFunctions.route()
          .POST(
              "/__local_test__/clock",
              request -> {
                String supplied = request.headers().firstHeader("X-Fixture-Key");
                if (supplied == null
                    || !MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8)))
                  return ServerResponse.status(403).build();
                Instant value = Instant.parse(request.body(Map.class).get("now").toString());
                if (Duration.between(Instant.now(), value).abs().toDays() > 7)
                  return ServerResponse.badRequest().build();
                now.set(value);
                return ServerResponse.ok().body(Map.of("accepted", true));
              })
          .build();
    }
  }
}
