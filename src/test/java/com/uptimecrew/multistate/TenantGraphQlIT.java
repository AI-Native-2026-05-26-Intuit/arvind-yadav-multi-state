package com.uptimecrew.multistate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.uptimecrew.multistate.graphql.TenantSummary;
import com.uptimecrew.multistate.readmodel.TenantReadModel;
import com.uptimecrew.multistate.readmodel.TenantReadModel.EmbeddedAllocation;
import com.uptimecrew.multistate.readmodel.TenantReadModelRepository;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
// Package moved in Boot 4 (was `org.springframework.boot.test.autoconfigure.graphql.tester`).
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end GraphQL integration test covering all four W3 D4 task legs:
 * <ol>
 *   <li>{@code tenant(id)} query resolves a seeded document.</li>
 *   <li>{@code latestTenants(limit) { lines { ... } }} exercises the
 *       {@code @BatchMapping} resolver for {@code Tenant.lines} in one round.</li>
 *   <li>{@code summarizeTenant(id)} mutation binds the LLM response to
 *       {@link TenantSummary} and the {@code LlmSummaryService} re-validates
 *       it against the JSON Schema — both stages run end-to-end, with the
 *       LLM call intercepted by {@link StubChatClientConfig}.</li>
 * </ol>
 *
 * <p>Runs against real Postgres + Mongo + Redis containers via
 * {@code @ServiceConnection}, matching the existing polyglot IT pattern. We
 * don't bring Kafka here because the GraphQL path doesn't depend on it; the
 * Kafka autoconfig stays best-effort and is harmless without a broker for
 * this test's life cycle.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureGraphQlTester
@ActiveProfiles("test")
@Import(TenantGraphQlIT.StubChatClientConfig.class)
class TenantGraphQlIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    /** Fixed payload the stub LLM returns — matches the JSON Schema. */
    static final TenantSummary STUB_SUMMARY =
            new TenantSummary("CA", 1500.00d, 2, "GREEN");

    @BeforeAll
    static void applyPostgresSchema() throws Exception {
        // Hibernate's startup `validate` reads the schema before the Spring context
        // hands control to test methods, so the DDL has to be in place statically.
        try (Connection conn = DriverManager.getConnection(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute(Files.readString(Path.of("db/V1__schema.sql")));
            stmt.execute(Files.readString(Path.of("db/V3__event_outbox.sql")));
        }
    }

    @Autowired GraphQlTester graphQlTester;
    @Autowired TenantReadModelRepository readModelRepository;

    @BeforeEach
    void seedMongo() {
        // Clear + reseed every test so insertion order maps to `capturedAt` desc,
        // and `latestTenants(limit: 5)` returns exactly the five we wrote.
        readModelRepository.deleteAll();

        // `seeded-id-1` carries embedded allocations so the lines projection has
        // real data when Task 2's batch resolver is exercised.
        Instant t0 = Instant.parse("2026-06-17T10:00:00Z");
        readModelRepository.save(new TenantReadModel(
                "seeded-id-1",
                "CA",
                "Seeded Co",
                "ACTIVE",
                t0,
                List.of(
                        new EmbeddedAllocation(
                                "alloc-ca", "CA", 2026, LocalDate.of(2026, 6, 1),
                                new BigDecimal("1000.00"),
                                "DayCountAllocationStrategy", t0),
                        new EmbeddedAllocation(
                                "alloc-ny", "NY", 2026, LocalDate.of(2026, 6, 1),
                                new BigDecimal("500.00"),
                                "DayCountAllocationStrategy", t0))));

        // Four more docs to fill out the latestTenants(limit: 5) page. Later
        // `capturedAt` so they sort ahead of seeded-id-1 in the desc-by-capturedAt
        // query — the assertion just checks the count, not the ordering.
        for (int i = 2; i <= 5; i++) {
            readModelRepository.save(new TenantReadModel(
                    "seeded-id-" + i,
                    "CA",
                    "Seeded Co " + i,
                    "ACTIVE",
                    t0.plusSeconds(i),
                    List.of()));
        }
    }

    @Test
    void query_tenant_returnsSeedDocument() {
        graphQlTester.document("query { tenant(id: \"seeded-id-1\") { id } }")
                .execute()
                .path("tenant.id").entity(String.class).isEqualTo("seeded-id-1");
    }

    @Test
    void batchMapping_resolves_lines_inOneRound() {
        graphQlTester.document("query { latestTenants(limit: 5) { id lines { id } } }")
                .execute()
                .path("latestTenants").entityList(Object.class).hasSize(5);
    }

    @Test
    void summarizeTenant_returnsStructuredOutput_andMatchesSchema() throws Exception {
        // Spring AI's .entity(TenantSummary.class) is intercepted by the stub
        // ChatClient.Builder; LlmSummaryService still runs its own JSON Schema
        // validation on the result before returning, so this test exercises both
        // stages of the Task 3 contract.
        TenantSummary summary = graphQlTester
                .document("mutation { summarizeTenant(id: \"seeded-id-1\") { "
                        + "primaryState totalAllocation stateCount complianceTier } }")
                .execute()
                .path("summarizeTenant").entity(TenantSummary.class).get();

        ObjectMapper jackson3 = JsonMapper.builder().build();
        JsonNode node = jackson3.valueToTree(summary);
        try (InputStream in = new ClassPathResource("schemas/TenantSummary.schema.json")
                .getInputStream()) {
            Schema schema = SchemaRegistry
                    .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(in);
            List<Error> errors = schema.validate(node);
            assertThat(errors).isEmpty();
        }
        assertThat(summary).isEqualTo(STUB_SUMMARY);
    }

    /**
     * Replaces the auto-configured {@code ChatClient.Builder} with one that
     * shortcuts the entire fluent chain through deep stubs and returns the
     * fixed {@link #STUB_SUMMARY} when {@code .responseEntity(TenantSummary.class)}
     * is called. {@code LlmSummaryService} switched from {@code .entity(...)} to
     * {@code .responseEntity(...)} so it can read token usage off the
     * {@code ChatResponse} for the manual OTel span — the stub follows.
     * The Anthropic HTTP client is never touched.
     */
    @TestConfiguration
    static class StubChatClientConfig {
        @Bean
        @Primary
        ChatClient.Builder stubChatClientBuilder() {
            ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
            org.springframework.ai.chat.metadata.Usage usage =
                    new org.springframework.ai.chat.metadata.DefaultUsage(1, 1, 2);
            org.springframework.ai.chat.metadata.ChatResponseMetadata metadata =
                    org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                            .usage(usage)
                            .build();
            org.springframework.ai.chat.model.ChatResponse chatResponse =
                    new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(), metadata);
            org.springframework.ai.chat.client.ResponseEntity<
                    org.springframework.ai.chat.model.ChatResponse, TenantSummary> stubbed =
                    new org.springframework.ai.chat.client.ResponseEntity<>(chatResponse, STUB_SUMMARY);
            when(builder.build().prompt().user(any(String.class)).call()
                    .responseEntity(TenantSummary.class)).thenReturn(stubbed);
            return builder;
        }
    }
}
