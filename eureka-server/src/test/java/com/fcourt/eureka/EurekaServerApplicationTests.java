package com.fcourt.eureka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EurekaServerApplicationTests {
    @LocalServerPort
    int port;
    @Autowired
    Environment environment;

    @Test
    void contextLoadsAsStandaloneRegistry() {
        assertThat(environment.getProperty("eureka.client.register-with-eureka", Boolean.class)).isFalse();
        assertThat(environment.getProperty("eureka.client.fetch-registry", Boolean.class)).isFalse();
    }

    @Test
    void healthIsUp() {
        var response = RestClient.create("http://localhost:" + port).get().uri("/actuator/health")
                .retrieve().toEntity(String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("UP");
    }

    @Test
    void registryEndpointAnswers() {
        var response = RestClient.create("http://localhost:" + port).get().uri("/eureka/apps")
                .header("Accept", "application/json").retrieve().toEntity(String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
