package com.uptimecrew.multistate;

import static org.assertj.core.api.Assertions.assertThat;

import com.uptimecrew.multistate.entity.Tenant;
import com.uptimecrew.multistate.model.WorkDay;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test (IT suffix) that boots a real Spring application context under the
 * {@code test} profile and exercises {@link AllocationService} through the injected bean
 * (not a hand-wired instance). The strategy that runs is whichever is marked {@code @Primary}
 * — currently {@code DayCountAllocationStrategy}, which splits income by day count.
 *
 * <p>Now that Spring Data JPA is on the classpath the context needs a real {@code DataSource},
 * so the test runs against a throwaway Postgres container whose schema and seed match the
 * W2 D1 DDL. {@code ddl-auto=validate} therefore also validates the Task-1 entity mappings
 * against the real tables.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
// The base profile excludes DataSourceAutoConfiguration (the app starts without a DB);
// JPA needs one here, so clear the exclusion for this test.
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
class ApplicationContextLoadIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofSeconds(120));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) throws Exception {
        // The container is started by the Testcontainers extension before the Spring
        // context is built. Load the schema + seed here — before Hibernate's
        // ddl-auto=validate runs — so validation sees the real multistate tables and the
        // FK target rows (jurisdiction) the persistence test relies on.
        applySchemaAndSeed();
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
    }

    // Mirrors TenantQueryIT: apply V1 schema, then V2 seed minus the trailing
    // intentional-failure block that pgjdbc surfaces as an error.
    private static void applySchemaAndSeed() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute(Files.readString(Path.of("db/V1__schema.sql")));
            // V3 adds the event_outbox table; Hibernate's validate mode requires it
            // because EventOutboxEntity is part of the persistence unit.
            stmt.execute(Files.readString(Path.of("db/V3__event_outbox.sql")));
            String seed = Files.readString(Path.of("db/V2__seed.sql"));
            int cut = seed.indexOf("-- Intentional failure test");
            stmt.execute(cut > 0 ? seed.substring(0, cut) : seed);
        }
    }

    @Autowired AllocationService service;

    @Test
    void context_loads_and_service_bean_is_wired() {
        assertThat(service)
            .as("Spring-managed AllocationService should be wired by the context")
            .isNotNull();
    }

    @Test
    void service_persists_tenant_with_primary_jurisdiction_from_largest_share() {
        // Arrange: two days in CA, one in NY — the @Primary day-count strategy gives CA
        // the larger share ($600 vs $300), so CA becomes the persisted tenant's primary
        // jurisdiction. CA is a seeded jurisdiction, so the FK is satisfied.
        String workerId = "emp_001";
        LocalDate allocatedFor = LocalDate.of(2026, 6, 9);
        List<WorkDay> workDays = List.of(
            new WorkDay("wd_1", workerId, "CA", LocalDate.of(2026, 6, 1)),
            new WorkDay("wd_2", workerId, "CA", LocalDate.of(2026, 6, 2)),
            new WorkDay("wd_3", workerId, "NY", LocalDate.of(2026, 6, 3))
        );

        Tenant saved =
            service.allocate(workerId, "Acme LLC", new BigDecimal("900.00"), workDays, allocatedFor);

        assertThat(saved.getId()).isEqualTo(workerId);
        assertThat(saved.getPrimaryJurisdictionCode())
            .as("CA received the larger share, so it is the primary jurisdiction")
            .isEqualTo("CA");
        assertThat(saved.getIncorporatedOn()).isEqualTo(allocatedFor);
    }
}
