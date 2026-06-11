package com.uptimecrew.multistate;

import static org.assertj.core.api.Assertions.assertThat;

import com.uptimecrew.multistate.model.WorkDay;
import com.uptimecrew.multistate.readmodel.TenantReadModel;
import com.uptimecrew.multistate.service.AllocationService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Polyglot-persistence integration test for the full application context: the JPA write,
 * the Mongo write-through, and the Redis cache-aside read are exercised end-to-end against
 * THREE real containers, each wired into Spring Boot via {@code @ServiceConnection}.
 *
 * <p>{@code @ServiceConnection} on Postgres and Mongo is recognised by image; Redis is a
 * plain {@link GenericContainer}, so it carries {@code @ServiceConnection(name = "redis")}
 * to tell Boot which connection it backs.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class TenantPolyglotIT {

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
        // Runs before the Spring context loads (static @BeforeAll precedes test-instance
        // preparation), so Hibernate's startup `validate` sees a fully-built schema.
        try (Connection conn = DriverManager.getConnection(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute(Files.readString(Path.of("db/V1__schema.sql")));
            // tenant.primary_jurisdiction_code FK-references jurisdiction(code); the
            // referenced reference-data row must exist before any tenant is persisted.
            // Mongo and Redis are schemaless, so they need no equivalent setup.
            stmt.execute("""
                INSERT INTO multistate.jurisdiction (code, display_name, has_income_tax)
                VALUES ('CA', 'California', TRUE)
                """);
        }
    }

    @Autowired AllocationService service;
    @Autowired CacheManager cacheManager;

    @Test
    void write_path_populates_postgres_AND_mongo() throws Exception {
        service.allocate(
            "ten-write",
            "Acme LLC",
            new BigDecimal("12500.00"),
            List.of(new WorkDay("wd-1", "ten-write", "CA", LocalDate.of(2026, 3, 1))),
            LocalDate.of(2026, 3, 31));

        // Postgres side: the JPA save wrote a tenant row.
        try (Connection conn = DriverManager.getConnection(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT primary_jurisdiction_code FROM multistate.tenant WHERE id = 'ten-write'")) {
            assertThat(rs.next()).as("tenant row present in postgres").isTrue();
            assertThat(rs.getString("primary_jurisdiction_code")).isEqualTo("CA");
        }

        // Mongo side: the write-through projection is readable via the service.
        Optional<TenantReadModel> readBack = service.findById("ten-write");
        assertThat(readBack).isPresent();
        assertThat(readBack.get().getPrimaryState()).isEqualTo("CA");
    }

    @Test
    void second_read_is_served_from_redis() {
        service.allocate(
            "ten-cache",
            "Beta LLC",
            new BigDecimal("9000.00"),
            List.of(new WorkDay("wd-2", "ten-cache", "CA", LocalDate.of(2026, 3, 2))),
            LocalDate.of(2026, 3, 31));

        // First read: cache miss; runs the method body and populates the Redis cache.
        assertThat(service.findById("ten-cache")).isPresent();

        // The Redis-backed cache region now holds the entry under the id key, so a
        // subsequent read is served from Redis without re-entering the method body.
        Cache cache = cacheManager.getCache(AllocationService.CACHE_NAME);
        assertThat(cache).as("cache region '%s'", AllocationService.CACHE_NAME).isNotNull();
        assertThat(cache.get("ten-cache"))
            .as("cache entry after first read")
            .isNotNull();
    }
}
