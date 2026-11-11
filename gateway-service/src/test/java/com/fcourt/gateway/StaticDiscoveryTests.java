package com.fcourt.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.test.context.ActiveProfiles;

/** There is no service registry: lb:// names resolve through the static instances in application.yml. */
@SpringBootTest
@ActiveProfiles("test")
class StaticDiscoveryTests {
    @Autowired
    DiscoveryClient discoveryClient;

    @Test
    void resolvesServiceByDnsNameAndPort() {
        List<ServiceInstance> instances = discoveryClient.getInstances("auth-service");
        assertThat(instances).hasSize(1);
        assertThat(instances.get(0).getUri().toString()).isEqualTo("http://auth-service:8081");
    }

    @Test
    void listsEveryRoutedService() {
        assertThat(discoveryClient.getServices()).contains(
                "auth-service", "subscription-service", "facility-service", "reporting-service", "discovery-service",
                "voucher-service", "slot-hold-engine", "booking-service", "payment-service",
                "staff-operations-service", "notification-service", "pos-retail-service");
    }
}
