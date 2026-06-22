package com.uptimecrew.multistate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.uptimecrew.multistate.graphql.TenantSummary;
import com.uptimecrew.multistate.model.WorkDay;
import com.uptimecrew.multistate.readmodel.TenantReadModel;
import com.uptimecrew.multistate.readmodel.TenantReadModelRepository;
import com.uptimecrew.multistate.service.AllocationService;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Trace-continuity integration test backed by an in-process
 * {@link InMemorySpanExporter}. We replace the production OpenTelemetry bean
 * with an SDK whose only span processor is a {@link SimpleSpanProcessor}
 * publishing to the in-memory exporter, so finished spans are visible
 * synchronously after every action — no scraping Jaeger over HTTP.
 *
 * <p>Five real containers via {@code @ServiceConnection} plus a Jaeger
 * all-in-one {@code GenericContainer}. The Jaeger container isn't actually
 * read by these assertions (the SDK exporter is the source of truth) — it
 * exists so any real OTLP-exporting OTel autoconfig left in the context has
 * a sink, matching production wiring.
 *
 * <p>Deviations from the reference shape, all driven by what this codebase
 * actually exposes today:
 * <ul>
 *   <li>{@link TenantSummary} has four fields and no Confidence enum, so the
 *       stub returns a real-shape record.</li>
 *   <li>{@code LlmSummaryService} calls {@code .responseEntity(TenantSummary.class)},
 *       not {@code .entity(...)} — the stub returns a {@link ResponseEntity}
 *       carrying a {@link ChatResponse} with non-null {@link Usage} so the
 *       {@code llm.tokens.in / out} attributes are populated.</li>
 *   <li>There is no {@code POST /api/v1/tenants} endpoint. Trigger the
 *       outbox → Kafka → consumer → Mongo chain by calling
 *       {@link AllocationService#allocate(String, String, BigDecimal, java.util.List, LocalDate)}
 *       directly, matching {@code TenantEventFlowIT}.</li>
 *   <li>{@code OutboxPublisher.publishPending()} runs on a 1s
 *       {@code @Scheduled} tick, each tick producing its own root trace.
 *       Asserting "exactly one trace id covers all emitted spans" would flake
 *       any time a poll lands inside the await window. Instead we assert
 *       there is at least one trace id that covers ≥ 5 spans AND contains
 *       both the Kafka producer and consumer spans — which is the real
 *       continuity claim.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureGraphQlTester
@Import(TenantObservabilityIT.TestOtelConfig.class)
class TenantObservabilityIT {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container @ServiceConnection
    static final MongoDBContainer MONGO =
            new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @Container @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    // Kafka has no ConnectionDetailsFactory in the Boot 4 testcontainers jar —
    // bind bootstrap.servers via @DynamicPropertySource, same shape as
    // TenantEventFlowIT.
    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    // Jaeger as a GenericContainer because there is no
    // org.testcontainers:jaegertracing module — we just need a live OTLP
    // receiver in case any leftover OTel autoconfig tries to export. The
    // InMemorySpanExporter inside TestOtelConfig is what the assertions read.
    @Container
    static final GenericContainer<?> JAEGER =
            new GenericContainer<>(DockerImageName.parse("jaegertracing/all-in-one:1.62.0"))
                    .withEnv("COLLECTOR_OTLP_ENABLED", "true")
                    .withExposedPorts(16686, 4317, 4318);

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // Point any residual OTLP exporter at the Jaeger container so it
        // doesn't fail to flush against an unreachable localhost:4318.
        registry.add("otel.exporter.otlp.endpoint",
                () -> "http://" + JAEGER.getHost() + ":" + JAEGER.getMappedPort(4318));
    }

    /** Fixed stub response shaped to the real four-field TenantSummary record. */
    static final TenantSummary STUB_SUMMARY =
            new TenantSummary("CA", 1500.00d, 2, "GREEN");

    static final long STUB_PROMPT_TOKENS = 137L;
    static final long STUB_COMPLETION_TOKENS = 42L;

    @BeforeAll
    static void applyPostgresSchema() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute(Files.readString(Path.of("db/V1__schema.sql")));
            stmt.execute(Files.readString(Path.of("db/V3__event_outbox.sql")));
            // FK target — the Kafka-chain test allocates with primaryJurisdictionCode='CA'.
            stmt.execute("""
                INSERT INTO multistate.jurisdiction (code, display_name, has_income_tax)
                VALUES ('CA', 'California', TRUE)
                """);
        }
    }

    @Autowired InMemorySpanExporter spanExporter;
    @Autowired GraphQlTester graphQlTester;
    @Autowired AllocationService allocationService;
    @Autowired TenantReadModelRepository readModelRepository;

    @Value("${local.server.port}") int port;

    // RestClient instead of the removed-in-Boot-4 TestRestTemplate. We just
    // need a real HTTP round-trip so the OTel Spring MVC server instrumentation
    // emits a server span.
    private RestClient http() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, resp) -> {})
                .build();
    }

    @BeforeEach
    void resetState() {
        // Drain whatever scheduled outbox-poll spans the SDK accumulated since
        // the last test so each assertion sees only spans from this turn.
        spanExporter.reset();
    }

    @Test
    void httpRequest_emits_serverSpan_and_jdbcChildSpan() {
        // Don't seed first — AllocationService.findById is @Cacheable on Redis,
        // so a prior seed would populate the cache and the GET would skip
        // Mongo/Postgres entirely, yielding no JDBC child. Use a fresh id so
        // findById misses the cache, misses Mongo, then falls back to JPA —
        // producing a JDBC SELECT child of the HTTP server span.
        String freshId = "trace-it-http-" + UUID.randomUUID();

        org.springframework.http.ResponseEntity<String> response = http()
                .get()
                .uri("/api/v1/tenants/" + freshId)
                .header("Authorization", "Bearer test-token")
                .retrieve()
                .toEntity(String.class);
        // We don't care about the status code — only that an HTTP server span
        // was emitted with a JDBC child sharing its trace id.
        assertThat(response.getStatusCode().value()).isBetween(200, 499);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertThat(spans).isNotEmpty();

            SpanData server = spans.stream()
                    .filter(s -> s.getName().startsWith("GET")
                            && s.getName().contains("/api/v1/tenants"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "expected an HTTP server span for GET /api/v1/tenants/{id}; "
                            + "saw " + spans.stream().map(SpanData::getName).toList()));

            boolean hasJdbcChild = spans.stream()
                    .anyMatch(s -> s.getTraceId().equals(server.getTraceId())
                            && (s.getName().toLowerCase().contains("select")
                                || s.getInstrumentationScopeInfo().getName().toLowerCase().contains("jdbc")));

            assertThat(hasJdbcChild)
                    .as("expected at least one JDBC child span sharing trace=%s; saw %s",
                            server.getTraceId(),
                            spans.stream().map(s -> s.getName() + "@" + s.getTraceId()).toList())
                    .isTrue();
        });
    }

    @Test
    void kafkaWriteThrough_singleTraceId_endToEnd() {
        // AllocationService.allocate(...) is the only entry point that fires
        // the outbox → Kafka → consumer → Mongo chain end-to-end; there's no
        // POST endpoint for this in the codebase.
        String aggregateId = "trace-it-kafka-" + UUID.randomUUID();
        allocationService.allocate(
                aggregateId,
                "Acme LLC",
                new BigDecimal("12500.00"),
                List.of(new WorkDay("wd-1", aggregateId, "CA", LocalDate.of(2026, 3, 1))),
                LocalDate.of(2026, 3, 31));

        // Wait for the consumer to write the Mongo doc — that's the last hop
        // in the chain, so once it's present every span has fired.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(readModelRepository.findById(aggregateId)).isPresent());

        // Pause briefly to let any in-flight Mongo client span flush through
        // the SimpleSpanProcessor before snapshotting.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<SpanData> all = spanExporter.getFinishedSpanItems();
            // Group by trace id; the chain we care about is the trace that
            // carries both a Kafka producer and consumer span (continuity).
            // Other trace ids in the snapshot are scheduled outbox polls that
            // happened to fire during the test — they are unrelated background
            // ticks and would make a strict hasSize(1) assertion flaky.
            Set<String> chainTraceIds = all.stream()
                    .filter(TenantObservabilityIT::isProducerSpan)
                    .map(SpanData::getTraceId)
                    .collect(Collectors.toSet());
            assertThat(chainTraceIds)
                    .as("expected exactly one Kafka producer trace from this allocate()")
                    .hasSize(1);

            String chainTraceId = chainTraceIds.iterator().next();
            List<SpanData> chain = all.stream()
                    .filter(s -> s.getTraceId().equals(chainTraceId))
                    .toList();

            assertThat(chain)
                    .as("the chain trace should carry >= 5 spans "
                            + "(internal outbox tick + select event_outbox + update event_outbox "
                            + "+ kafka publish + kafka process + mongo upsert)")
                    .hasSizeGreaterThanOrEqualTo(5);
            assertThat(chain.stream().anyMatch(TenantObservabilityIT::isProducerSpan))
                    .as("chain trace must contain a Kafka producer span").isTrue();
            assertThat(chain.stream().anyMatch(TenantObservabilityIT::isConsumerSpan))
                    .as("chain trace must contain a Kafka consumer span").isTrue();
        });
    }

    @Test
    void llmSummarize_spanHasTokenAttributes() {
        seedTenantViaService("trace-it-llm");
        spanExporter.reset();

        graphQlTester
                .document("mutation { summarizeTenant(id: \"trace-it-llm\") { "
                        + "primaryState totalAllocation stateCount complianceTier } }")
                .execute()
                .path("summarizeTenant.primaryState").entity(String.class).isEqualTo("CA");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            SpanData llm = spanExporter.getFinishedSpanItems().stream()
                    .filter(s -> "llm.summarize".equals(s.getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no llm.summarize span emitted"));

            assertThat(llm.getAttributes()
                    .get(AttributeKey.stringKey("llm.model")))
                    .as("llm.model attribute")
                    .isNotBlank();
            assertThat(llm.getAttributes()
                    .get(AttributeKey.stringKey("llm.input.aggregate_id")))
                    .as("llm.input.aggregate_id attribute")
                    .isEqualTo("trace-it-llm");
            assertThat(llm.getAttributes()
                    .get(AttributeKey.longKey("llm.tokens.in")))
                    .as("llm.tokens.in attribute")
                    .isEqualTo(STUB_PROMPT_TOKENS);
            assertThat(llm.getAttributes()
                    .get(AttributeKey.longKey("llm.tokens.out")))
                    .as("llm.tokens.out attribute")
                    .isEqualTo(STUB_COMPLETION_TOKENS);
        });
    }

    private void seedTenantViaService(String id) {
        // Reuse the real service so JPA + Mongo write-through fire — the
        // tenant exists in both stores when subsequent tests look it up.
        try {
            allocationService.allocate(
                    id,
                    "Seeded " + id,
                    new BigDecimal("1500.00"),
                    List.of(new WorkDay("wd-seed-" + id, id, "CA",
                            LocalDate.of(2026, 3, 1))),
                    LocalDate.of(2026, 3, 31));
        } catch (RuntimeException ignored) {
            // Some tests re-seed the same id across runs; allocate() is
            // idempotent enough for the read path that a duplicate-key blip
            // is not fatal here.
            if (readModelRepository.findById(id).isEmpty()) {
                readModelRepository.save(new TenantReadModel(
                        id, "CA", "Seeded " + id, "ACTIVE", Instant.now(), List.of()));
            }
        }
    }

    private static boolean isProducerSpan(SpanData s) {
        String dest = s.getAttributes().get(AttributeKey.stringKey("messaging.destination.name"));
        return "PRODUCER".equals(s.getKind().name()) && "tenants.events".equals(dest);
    }

    private static boolean isConsumerSpan(SpanData s) {
        String dest = s.getAttributes().get(AttributeKey.stringKey("messaging.destination.name"));
        return "CONSUMER".equals(s.getKind().name()) && "tenants.events".equals(dest);
    }

    @TestConfiguration
    static class TestOtelConfig {

        // Override the OpenTelemetry bean with an SDK whose only exporter is
        // InMemorySpanExporter. SimpleSpanProcessor (not BatchSpanProcessor)
        // so finished spans show up in spanExporter.getFinishedSpanItems()
        // immediately after the action returns — BatchSpanProcessor's default
        // 500 ms delay would force every test to sleep.
        @Bean @Primary
        OpenTelemetry openTelemetry(InMemorySpanExporter exporter) {
            SdkTracerProvider provider = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build();
            return OpenTelemetrySdk.builder()
                    .setTracerProvider(provider)
                    .setPropagators(io.opentelemetry.context.propagation.ContextPropagators.create(
                            io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance()))
                    .build();
        }

        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }

        // Replace the auto-configured ChatClient.Builder with one whose
        // .responseEntity(TenantSummary.class) returns a ResponseEntity
        // carrying both the parsed record AND a ChatResponse with non-null
        // Usage — LlmSummaryService reads Usage.getPromptTokens() /
        // getCompletionTokens() to populate llm.tokens.in / out. Without
        // non-null usage those span attributes would be 0 (or absent).
        @Bean @Primary
        ChatClient.Builder stubChatClientBuilder() {
            ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);

            Usage usage = new DefaultUsage(
                    (int) STUB_PROMPT_TOKENS,
                    (int) STUB_COMPLETION_TOKENS,
                    (int) (STUB_PROMPT_TOKENS + STUB_COMPLETION_TOKENS));
            ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                    .usage(usage)
                    .build();
            // Build a minimal ChatResponse — Generations don't matter to
            // LlmSummaryService once .responseEntity() returns the parsed
            // entity directly. The metadata is what the service reads.
            ChatResponse chatResponse = new ChatResponse(List.of(), metadata);

            ResponseEntity<ChatResponse, TenantSummary> stubbed =
                    new ResponseEntity<>(chatResponse, STUB_SUMMARY);

            when(builder.build().prompt().user(any(String.class)).call()
                    .responseEntity(TenantSummary.class))
                    .thenReturn(stubbed);
            return builder;
        }

        // Replace the production JwtDecoder (which would try to fetch JWKs from
        // an unreachable issuer-uri) with one that trusts any string and emits
        // a Jwt carrying scope=tenants.read + roles=[TENANT_READER]. The
        // controller's @AuthenticationPrincipal Jwt and @PreAuthorize check
        // both then see a valid principal — no signed-JWT plumbing required.
        @Bean @Primary
        JwtDecoder testJwtDecoder() {
            return token -> Jwt.withTokenValue(token == null || token.isBlank() ? "test" : token)
                    .header("alg", "none")
                    .subject("test-observability")
                    .claim("scope", "tenants.read")
                    .claim("roles", List.of("TENANT_READER"))
                    .issuedAt(java.time.Instant.now())
                    .expiresAt(java.time.Instant.now().plusSeconds(3600))
                    .build();
        }
    }
}
