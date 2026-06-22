package com.uptimecrew.multistate;

import static org.assertj.core.api.Assertions.assertThat;

import com.uptimecrew.multistate.readmodel.TenantReadModel;
import com.uptimecrew.multistate.readmodel.TenantReadModelRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// Package moved in Boot 4 (was `org.springframework.boot.test.autoconfigure.graphql.tester`).
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end GraphQL IT for the new {@code tenantsByTag(tag)} query.
 *
 * <p>Mirrors {@link TenantGraphQlIT}'s container wiring (Postgres + Mongo +
 * Redis via {@code @ServiceConnection}) and Postgres DDL bootstrap — the new
 * query path only touches Mongo, but the full Spring context still needs
 * Hibernate's schema validation to pass and a {@code ChatClient.Builder} to
 * exist (the LLM path is never exercised here, so we just re-import
 * {@link TenantGraphQlIT.StubChatClientConfig} rather than redefine it).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureGraphQlTester
@ActiveProfiles("test")
@Import(TenantGraphQlIT.StubChatClientConfig.class)
class TenantsByTagIT {

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
        // Clean slate every test so the derived `findByTagsContaining` returns
        // only the docs we wrote here, in a known shape.
        readModelRepository.deleteAll();

        Instant t0 = Instant.parse("2026-06-17T10:00:00Z");
        readModelRepository.save(new TenantReadModel(
                "tag-test-1", "CA", "Alpha Beta Co", "ACTIVE",
                t0, List.of(), List.of("alpha", "beta")));
        readModelRepository.save(new TenantReadModel(
                "tag-test-2", "CA", "Beta Gamma Co", "ACTIVE",
                t0.plusSeconds(1), List.of(), List.of("beta", "gamma")));
        readModelRepository.save(new TenantReadModel(
                "tag-test-3", "CA", "Delta Co", "ACTIVE",
                t0.plusSeconds(2), List.of(), List.of("delta")));
    }

    @Test
    void tenantsByTag_returnsOnlyDocsCarryingThatTag() {
        List<TaggedTenant> results = graphQlTester
                .document("query { tenantsByTag(tag: \"beta\") { id tags } }")
                .execute()
                .path("tenantsByTag").entityList(TaggedTenant.class).get();

        assertThat(results).hasSize(2);
        assertThat(results).extracting(TaggedTenant::id)
                .containsExactlyInAnyOrder("tag-test-1", "tag-test-2");
        assertThat(results).allSatisfy(t -> {
            assertThat(t.tags()).isNotNull();
            assertThat(t.tags()).contains("beta");
        });
    }

    /** Projection record matching the GraphQL selection set. */
    record TaggedTenant(String id, List<String> tags) {}
}
