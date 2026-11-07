package com.fcourt.auth.account;

import com.fcourt.auth.AuthApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

/** Never packaged in the production jar. External storage only is a fixture. */
public class LocalFixtureApplication {
  public static void main(String[] args) {
    if (!"true".equals(System.getenv("FCOURT_LOCAL_TEST")))
      throw new IllegalStateException("Local fixture opt-in required");
    SpringApplication.run(new Class<?>[] {AuthApplication.class, Fixture.class}, args);
  }

  @TestConfiguration
  static class Fixture {
    @Bean
    @Primary
    DocumentStorage fixtureDocuments() {
      return new DocumentStorage() {
        public String store(byte[] bytes, String folder) {
          return "https://res.cloudinary.com/local-fixture/image/upload/"
              + java.util.UUID.randomUUID()
              + ".png";
        }

        public void delete(String url) {}
      };
    }
  }
}
