package com.fcourt.staff;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.apache.kafka.clients.admin.*;
import org.testcontainers.containers.*;
import org.testcontainers.kafka.KafkaContainer;

/** Test-classpath-only infrastructure owned by tools/system_tests/local_cluster.py. */
public class LocalClusterInfrastructure {
  public static void main(String[] args) throws Exception {
    try (var db =
            new PostgreSQLContainer<>("postgres:16-alpine")
                .withUsername("postgres")
                .withPassword(UUID.randomUUID().toString())
                .withDatabaseName("postgres");
        var redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        var kafka = new KafkaContainer("apache/kafka-native:3.8.0")) {
      db.start();
      redis.start();
      kafka.start();
      try (var admin =
          AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
        admin
            .createTopics(
                List.of(
                        "fcourt.auth.events",
                        "fcourt.staff.events",
                        "fcourt.payment.events",
                        "fcourt.booking.events",
                        "facility.branch-updated.v1")
                    .stream()
                    .map(
                        name ->
                            new NewTopic(
                                name, name.equals("facility.branch-updated.v1") ? 3 : 1, (short) 1))
                    .toList())
            .all()
            .get();
      }
      System.out.println(
          "FCOURT_LOCAL_TEST="
              + new ObjectMapper()
                  .writeValueAsString(
                      Map.of(
                          "postgresPort",
                          db.getMappedPort(5432),
                          "postgresContainer",
                          db.getContainerId(),
                          "password",
                          db.getPassword(),
                          "redisPort",
                          redis.getMappedPort(6379),
                          "kafka",
                          kafka.getBootstrapServers())));
      System.out.flush();
      System.in.read();
    }
  }
}
