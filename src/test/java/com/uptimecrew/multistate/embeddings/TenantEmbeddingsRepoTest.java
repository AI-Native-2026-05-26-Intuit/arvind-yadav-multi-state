// src/test/java/com/uptimecrew/multistate/embeddings/TenantEmbeddingsRepoTest.java
//
// W2D2 callback: same Testcontainers shape as the Week 2 Day 2
// PostgreSQLContainer test; the only change is the image, from
// postgres:16 to pgvector/pgvector:pg16 (officially published by
// the pgvector project with the extension preinstalled on
// shared_preload_libraries).
//
// Determinism discipline (Section 9 sticking point #2):
//   * .withReuse(true) is on for performance.
//   * Every @Test method MUST TRUNCATE the table first so reuse
//     doesn't leak rows across methods.
//   * Seed vectors are hand-crafted axis-aligned values; the
//     query vector is closest to row 2 by construction; no
//     wallclock, no Random, no Math.random anywhere.
package com.uptimecrew.multistate.embeddings;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
final class TenantEmbeddingsRepoTest {

    @Container
    private static final PostgreSQLContainer<?> DB =
        new PostgreSQLContainer<>(
            DockerImageName
                .parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("multistate")
            .withUsername("uptimecrew")
            .withPassword("uptimecrew-test")
            .withReuse(true);

    @BeforeAll
    static void migrate() {
        // Runs the same Flyway migrations the production stack runs
        // against RDS -- drift between test and production is
        // impossible because the schema source is one file.
        Flyway.configure()
              .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
              .locations("classpath:db/migration")
              .load()
              .migrate();
    }

    @BeforeEach
    void truncateBetweenTests() throws Exception {
        // Container reuse without per-test cleanup is the
        // local-pass / CI-fail failure mode. TRUNCATE before each
        // test method keeps the suite order-independent.
        try (Connection c = DriverManager.getConnection(
                DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
             Statement s = c.createStatement()) {
            s.execute("TRUNCATE TABLE tenant_embeddings");
        }
    }

    @Test
    void cosineNearestReturnsSeededOrder() throws Exception {
        try (Connection c = DriverManager.getConnection(
                DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())) {

            // Seed three deterministic vectors. The query vector
            // [0.95, 0.05, 0, ...] is closest to row 2 by
            // construction (cosine distance is minimized for the
            // most-aligned vector pair).
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO tenant_embeddings(id, tenant_id, embedding) "
              + "VALUES (?, 'tenant-a', ?::vector)")) {
                insert(ps, "00000000-0000-0000-0000-000000000001",
                       axisAlignedUnit(0));        // [1, 0, 0, ...]
                insert(ps, "00000000-0000-0000-0000-000000000002",
                       slightlyOffAxis());          // [0.9, 0.1, 0, ...]
                insert(ps, "00000000-0000-0000-0000-000000000003",
                       axisAlignedUnit(1));         // [0, 1, 0, ...]
            }

            try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM tenant_embeddings "
              + "WHERE tenant_id = 'tenant-a' "
              + "ORDER BY embedding <=> ?::vector "
              + "LIMIT 1")) {
                ps.setString(1, queryNearRow2());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "expected at least one row");
                    assertEquals(
                        "00000000-0000-0000-0000-000000000002",
                        rs.getString(1),
                        "row 2 is closest to the query by construction");
                }
            }
        }
    }

    // --- deterministic vector helpers ---------------------------------

    private static String axisAlignedUnit(int axis) {
        // [0, 0, ..., 1 at index `axis`, ..., 0]
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 1024; i++) {
            if (i > 0) sb.append(',');
            sb.append(i == axis ? "1" : "0");
        }
        return sb.append(']').toString();
    }

    private static String slightlyOffAxis() {
        // [0.9, 0.1, 0, 0, ...]
        StringBuilder sb = new StringBuilder("[0.9,0.1");
        for (int i = 2; i < 1024; i++) sb.append(",0");
        return sb.append(']').toString();
    }

    private static String queryNearRow2() {
        // [0.95, 0.05, 0, 0, ...]
        StringBuilder sb = new StringBuilder("[0.95,0.05");
        for (int i = 2; i < 1024; i++) sb.append(",0");
        return sb.append(']').toString();
    }

    private static void insert(PreparedStatement ps,
                               String id,
                               String vec) throws Exception {
        ps.setObject(1, UUID.fromString(id));
        ps.setString(2, vec);
        ps.executeUpdate();
    }
}
