package com.uptimecrew.multistate.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantQueryIT {

    // Wait on the listening port instead of TC's default log wait. On Rancher
    // Desktop the log message can fire before the VM finishes publishing the
    // mapped port to host localhost, causing the first JDBC connect to refuse.
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofSeconds(120));

    @BeforeAll
    void applySchemaAndSeed() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute(Files.readString(Path.of("db/V1__schema.sql")));
            // The seed file deliberately ends with a BEGIN/INSERT-violating-check/ROLLBACK
            // block that proves the CHECK constraint fires. psql tolerates the error and
            // continues; pgjdbc surfaces it as PSQLException. Strip that trailing block so
            // the seed loads cleanly under JDBC.
            String seed = Files.readString(Path.of("db/V2__seed.sql"));
            int cut = seed.indexOf("-- Intentional failure test");
            stmt.execute(cut > 0 ? seed.substring(0, cut) : seed);
        }
    }

    @Test
    void cte_query_returns_tenants_above_threshold_ordered_by_total_desc() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(extractSelect("db/queries/cte.sql"))) {

            List<Tuple> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(tuple(
                        rs.getString("tenant_id"),
                        rs.getString("status"),
                        rs.getBigDecimal("total_amount").setScale(2)));
            }

            assertThat(rows)
                .as("CTE: tenants whose summed allocation amount exceeds 100.00, ordered DESC")
                .containsExactly(
                    tuple("ten-a", "ACTIVE", new BigDecimal("33750.50")),
                    tuple("ten-b", "ACTIVE", new BigDecimal("20000.00")),
                    tuple("ten-d", "ACTIVE", new BigDecimal("13500.00")));
        }
    }

    @Test
    void group_by_having_returns_only_tenants_with_three_or_more_allocations() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(extractSelect("db/queries/group_by_having.sql"))) {

            List<Tuple> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(tuple(
                        rs.getString("tenant_id"),
                        rs.getLong("allocation_count")));
            }

            assertThat(rows)
                .as("GROUP BY/HAVING: only ten-a has >= 3 allocations in the seed")
                .hasSize(1)
                .containsExactly(tuple("ten-a", 3L));
        }
    }

    // The query files lead with comments and `SET search_path TO multistate, public;` so
    // they run cleanly in psql. JDBC's executeQuery requires a single result-bearing
    // statement, so we drop comment and SET lines and keep only the executable body —
    // every reference uses the fully-qualified `multistate.` schema so dropping the SET
    // doesn't change the result.
    private static String extractSelect(String path) throws Exception {
        StringBuilder out = new StringBuilder();
        for (String line : Files.readString(Path.of(path)).split("\n")) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("--") || trimmed.isEmpty()) continue;
            if (trimmed.toUpperCase().startsWith("SET ")) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }
}
