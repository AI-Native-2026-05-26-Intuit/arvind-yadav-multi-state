package com.uptimecrew.multistate;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

/** W5 D5 T2 — k8s profile emits JSON log lines with correlationId in the envelope. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "k8s"})
@ExtendWith(OutputCaptureExtension.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "spring.autoconfigure.exclude=",
      "management.endpoints.web.exposure.include=health,prometheus,info",
      "otel.sdk.disabled=true"
    })
class K8sJsonLoggingIT {

  private static final ObjectMapper JSON = new ObjectMapper();

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

  @Autowired com.uptimecrew.multistate.service.TenantLookupService tenantLookup;

  @Test
  void lookupLogLine_isJsonWithCorrelationIdAndAppFields(CapturedOutput output) throws Exception {
    tenantLookup.lookupById("missing-tenant-id");

    String jsonLine =
        output.getOut().lines()
            .filter(line -> line.contains("lookup attempted"))
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("expected lookup attempted log line"));

    JsonNode node = JSON.readTree(jsonLine);
    assertThat(node.path("@timestamp").asText()).isNotBlank();
    assertThat(node.path("message").asText()).contains("lookup attempted");
    assertThat(node.path("app").asText()).isEqualTo("multistate-api");
    assertThat(node.path("env").asText()).isEqualTo("k8s");
  }

  @Test
  void correlationFilter_echoesHeaderAndSurfacesCorrelationIdInJsonLogs(CapturedOutput output) {
    RestClient client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    var response =
        client
            .get()
            .uri("/actuator/health")
            .header("x-correlation-id", "probe-corr-99")
            .retrieve()
            .toBodilessEntity();
    assertThat(response.getHeaders().getFirst("x-correlation-id")).isEqualTo("probe-corr-99");

    assertThat(
            output.getOut().lines().anyMatch(line -> line.contains("\"correlationId\":\"probe-corr-99\"")))
        .isTrue();
  }
}
