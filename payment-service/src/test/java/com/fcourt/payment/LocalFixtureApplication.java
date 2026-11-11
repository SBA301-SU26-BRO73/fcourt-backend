package com.fcourt.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

/** Test-classpath-only replacement of the external receipt provider. */
public class LocalFixtureApplication {
  public static void main(String[] args) {
    if (!"true".equals(System.getenv("FCOURT_LOCAL_TEST")))
      throw new IllegalStateException("Local fixture opt-in required");
    SpringApplication.run(new Class<?>[] {PaymentApplication.class, Fixture.class}, args);
  }

  @TestConfiguration
  static class Fixture {
    @Bean
    @Primary
    ReceiptStorage fixtureReceipts() {
      return new ReceiptStorage() {
        public String upload(byte[] bytes) {
          return "receipts/" + java.util.UUID.randomUUID() + ".png";
        }

        public String uploadRefundProof(byte[] bytes) {
          return "refund-proofs/" + java.util.UUID.randomUUID() + ".png";
        }

        public String signedUrl(String stored) {
          return "http://localhost/fixture/" + stored + "?signed";
        }
      };
    }
  }
}
