package com.uptimecrew.multistate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uptimecrew.multistate.model.WorkDay;
import com.uptimecrew.multistate.outbox.EventOutboxRepository;
import com.uptimecrew.multistate.readmodel.TenantReadModelRepository;
import com.uptimecrew.multistate.service.AllocationService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end event-flow integration test against four real containers:
 * Postgres, Mongo, Redis, and Kafka. Each is wired into Spring Boot via
 * {@code @ServiceConnection}, so the application code sees its production
 * config — no test-only overrides for the outbox + listener pipeline.
 *
 * <p>Three scenarios:
 * <ol>
 *   <li>A domain write goes through {@code AllocationService.allocate(...)};
 *       the outbox row commits, {@link com.uptimecrew.multistate.outbox.OutboxPublisher}
 *       picks it up on its 1s schedule, and the record lands on
 *       {@code tenants.events} keyed by the aggregate id.</li>
 *   <li>A directly-produced payload on {@code tenants.events} flows through
 *       {@link com.uptimecrew.multistate.consumer.AllocationCreatedListener}
 *       and produces a Mongo document — proving the consumer path is the
 *       sole way the read model can be re-projected from the topic.</li>
 *   <li>A poison-pill payload on {@code tenants.events} fails Jackson parsing
 *       inside the listener, retries 3 times (FixedBackOff(1000ms, 3)), then
 *       lands on {@code tenants.events.DLT} via the
 *       {@code DeadLetterPublishingRecoverer}.</li>
 * </ol>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class TenantEventFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO =
            new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    // No @ServiceConnection: Boot 4.0.6's spring-boot-testcontainers jar does
    // not ship a Kafka ConnectionDetailsFactory (only Postgres/Mongo/Redis are
    // wired). We bind the container's bootstrap servers via @DynamicPropertySource
    // below — both KafkaProducerConfig and KafkaErrorHandlingConfig read
    // spring.kafka.bootstrap-servers via @Value, so a property override is
    // sufficient.
    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeAll
    static void applyPostgresSchema() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute(Files.readString(Path.of("db/V1__schema.sql")));
            stmt.execute(Files.readString(Path.of("db/V3__event_outbox.sql")));
            // FK target for primary_jurisdiction_code — production code uses 'CA' as
            // the largest-share jurisdiction in this test's allocate(...) input.
            stmt.execute("""
                INSERT INTO multistate.jurisdiction (code, display_name, has_income_tax)
                VALUES ('CA', 'California', TRUE)
                """);
        }
    }

    @Autowired AllocationService service;
    @Autowired EventOutboxRepository outboxRepository;
    @Autowired TenantReadModelRepository readModelRepository;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper mapper;

    @Test
    void write_publishes_to_kafka_via_outbox() throws Exception {
        String aggregateId = "agg-" + UUID.randomUUID();
        LocalDate allocatedFor = LocalDate.of(2026, 3, 31);

        service.allocate(
                aggregateId,
                "Acme LLC",
                new BigDecimal("12500.00"),
                List.of(new WorkDay("wd-1", aggregateId, "CA", LocalDate.of(2026, 3, 1))),
                allocatedFor);

        // OutboxPublisher runs on a 1s @Scheduled tick. Allow up to 5s for the
        // row to be stamped published_at.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(outboxRepository.findAll())
                .anyMatch(r -> r.getAggregateId().equals(aggregateId) && r.getPublishedAt() != null));

        // Independent probe consumer (separate groupId) reads the same partition
        // from the beginning so the assertion sees the record regardless of when
        // the app's listener got there.
        try (KafkaConsumer<String, String> probe = newProbe("probe-" + UUID.randomUUID(), "tenants.events")) {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                ConsumerRecord<String, String> rec = pollUntilMatch(probe, aggregateId);
                assertThat(rec).as("kafka record for aggregateId=%s", aggregateId).isNotNull();
                assertThat(rec.key()).isEqualTo(aggregateId);
            });
        }
    }

    @Test
    void consumer_updates_mongo_read_model() throws Exception {
        String aggregateId = "agg-" + UUID.randomUUID();
        // Payload that AllocationCreatedEvent can deserialize: producer-side field
        // is 'tenantId' (mapped to aggregateId on the consumer side via @JsonProperty).
        String payload = mapper.writeValueAsString(Map.of(
                "eventType", "TenantAllocated",
                "tenantId", aggregateId,
                "primaryJurisdictionCode", "CA",
                "status", "ACTIVE",
                "strategy", "SyntheticStrategy",
                "allocatedFor", "2026-03-31",
                "allocationCount", 1,
                "totalIncome", "100.00",
                "occurredAt", "2026-03-31T00:00:00Z"));
        kafkaTemplate.send("tenants.events", aggregateId, payload).get(5, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(readModelRepository.findById(aggregateId)).isPresent());
    }

    @Test
    void poison_pill_routes_to_dlt_after_retries() throws Exception {
        String aggregateId = "agg-" + UUID.randomUUID();
        kafkaTemplate.send("tenants.events", aggregateId, "{not valid json").get(5, TimeUnit.SECONDS);

        // FixedBackOff(1000ms, 3) -> ~3s of retries before the DLT publish. The
        // DLT topic is auto-created on first publish by the Kafka broker (default
        // auto.create.topics.enable on the Apache image), so the probe has to
        // wait for the recoverer to fire before subscribing won't see anything.
        try (KafkaConsumer<String, String> dlt = newProbe("dlt-" + UUID.randomUUID(), "tenants.events.DLT")) {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                ConsumerRecord<String, String> rec = pollUntilMatch(dlt, aggregateId);
                assertThat(rec).as("dlt record for aggregateId=%s", aggregateId).isNotNull();
                assertThat(rec.key()).isEqualTo(aggregateId);
            });
        }
    }

    private static KafkaConsumer<String, String> newProbe(String groupId, String topic) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        KafkaConsumer<String, String> c = new KafkaConsumer<>(props);
        c.subscribe(List.of(topic));
        return c;
    }

    /**
     * Poll until a record with the expected key is seen (filter out leftovers
     * from other tests running in the same suite under one container set).
     */
    private static ConsumerRecord<String, String> pollUntilMatch(
            KafkaConsumer<String, String> c, String expectedKey) {
        var records = c.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, String> rec : records) {
            if (expectedKey.equals(rec.key())) {
                return rec;
            }
        }
        return null;
    }
}
