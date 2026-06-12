package com.uptimecrew.multistate.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.uptimecrew.multistate.entity.Tenant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * JPA-slice integration test for {@link TenantRepository}.
 *
 * <p>{@code @DataJpaTest} by default replaces the DataSource with an in-memory H2;
 * {@code @AutoConfigureTestDatabase(replace=NONE)} keeps the real Testcontainers one,
 * and {@code @ServiceConnection} wires the container's URL/user/password into Spring.
 * Each {@code @Test} runs in a transaction that rolls back, so tenant rows never leak
 * between tests.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// The base profile excludes DataSourceAutoConfiguration (the app starts without a DB);
// the JPA slice needs it, so clear the exclusion for this test.
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
class TenantRepositoryIT {

    // Application carries @EnableCaching, but the JPA slice doesn't load the Redis cache
    // auto-config that supplies a CacheManager in the running app. Provide a no-op
    // in-memory one so the slice context starts; this test exercises JPA, not caching.
    @TestConfiguration
    static class CachingTestConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager();
        }
    }

    // Wait on the listening port (not TC's default log wait): on Rancher Desktop the log
    // message can fire before the mapped port is published, refusing the first connect.
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofSeconds(120));

    @BeforeAll
    static void applySchema() throws Exception {
        // Runs on its own (auto-commit) connection, outside the per-test transaction, so
        // the schema and the FK-target jurisdiction rows persist for the whole class.
        try (Connection conn = DriverManager.getConnection(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute(Files.readString(Path.of("db/V1__schema.sql")));
            // tenant.primary_jurisdiction_code FK-references jurisdiction(code), so the
            // referenced reference-data rows must exist before any tenant is saved.
            stmt.execute("""
                INSERT INTO multistate.jurisdiction (code, display_name, has_income_tax) VALUES
                    ('CA', 'California', TRUE),
                    ('NY', 'New York',   TRUE)
                """);
        }
    }

    @Autowired TenantRepository repository;

    private static Tenant tenant(String id, String primaryJurisdictionCode) {
        Instant now = Instant.now();
        return new Tenant(
            id,
            "legal-" + id,
            primaryJurisdictionCode,
            "ACTIVE",
            LocalDate.of(2020, 1, 1),
            now,
            now);
    }

    @Test
    void save_and_find_round_trip() {
        repository.save(tenant("test-001", "CA"));

        Optional<Tenant> found = repository.findById("test-001");

        assertThat(found).isPresent();
        assertThat(found.get())
            .extracting(Tenant::getId, Tenant::getLegalName, Tenant::getPrimaryJurisdictionCode, Tenant::getStatus)
            .containsExactly("test-001", "legal-test-001", "CA", "ACTIVE");
        assertThat(found.get().getIncorporatedOn()).isEqualTo(LocalDate.of(2020, 1, 1));
    }

    @Test
    void derived_finder_returns_only_matching_rows() {
        repository.save(tenant("a", "CA"));
        repository.save(tenant("b", "NY"));

        assertThat(repository.findByPrimaryJurisdictionCode("CA"))
            .extracting(Tenant::getId)
            .containsExactly("a");
    }
}
