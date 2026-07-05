package com.uptimecrew.multistate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies /actuator/prometheus exposes histogram + counter series (W5 D5 Task 1). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(
    properties = {
      "spring.autoconfigure.exclude=",
      "management.endpoints.web.exposure.include=health,prometheus,info",
      "management.metrics.tags.app=multistate-api",
      "otel.sdk.disabled=true"
    })
class ActuatorPrometheusIT {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .waitingFor(Wait.forListeningPort())
          .withStartupTimeout(Duration.ofSeconds(120));

  @Container @ServiceConnection static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

  @Container
  @ServiceConnection(name = "redis")
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void applySchema(DynamicPropertyRegistry ignored) throws Exception {
    try (Connection conn =
            DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Statement stmt = conn.createStatement()) {
      stmt.execute(Files.readString(Path.of("db/V1__schema.sql")));
      stmt.execute(Files.readString(Path.of("db/V3__event_outbox.sql")));
      String seed = Files.readString(Path.of("db/V2__seed.sql"));
      int cut = seed.indexOf("-- Intentional failure test");
      stmt.execute(cut > 0 ? seed.substring(0, cut) : seed);
    }
  }

  @LocalServerPort int port;

  @Test
  void prometheusEndpoint_exposesHttpServerRequestHistogramSeries() {
    RestClient client = RestClient.builder().baseUrl("http://localhost:" + port).build();

    client.get().uri("/actuator/health").retrieve().toBodilessEntity();
    client.get().uri("/actuator/health").retrieve().toBodilessEntity();

    ResponseEntity<String> prom =
        client.get().uri("/actuator/prometheus").retrieve().toEntity(String.class);

    assertThat(prom.getStatusCode().is2xxSuccessful()).isTrue();
    String body = prom.getBody();
    assertThat(body).isNotBlank();
    assertThat(body).contains("http_server_requests_seconds_count");
    assertThat(body).contains("http_server_requests_seconds_bucket");
    assertThat(body).contains("http_server_requests_seconds_sum");
    assertThat(body).contains("app=\"multistate-api\"");
  }
}
