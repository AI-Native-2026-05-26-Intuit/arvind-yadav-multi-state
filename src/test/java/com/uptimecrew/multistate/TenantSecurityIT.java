package com.uptimecrew.multistate;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.uptimecrew.multistate.model.WorkDay;
import com.uptimecrew.multistate.service.AllocationService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantSecurityIT {

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
        try (Connection conn = DriverManager.getConnection(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute(Files.readString(Path.of("db/V1__schema.sql")));
            // V3 adds the event_outbox table; Hibernate's validate mode requires it
            // because EventOutboxEntity is part of the persistence unit.
            stmt.execute(Files.readString(Path.of("db/V3__event_outbox.sql")));
            stmt.execute("""
                INSERT INTO multistate.jurisdiction (code, display_name, has_income_tax)
                VALUES ('CA', 'California', TRUE)
                """);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired AllocationService service;

    void seedTenant(String id) {
        service.allocate(
            id,
            "Acme LLC",
            new BigDecimal("10000.00"),
            List.of(new WorkDay("wd-" + id, id, "CA", LocalDate.of(2026, 3, 1))),
            LocalDate.of(2026, 3, 31));
    }

    @Test
    void getById_returns200_whenAuthenticatedWithScopeAndRole() throws Exception {
        seedTenant("test-id");
        mvc.perform(get("/api/v1/tenants/test-id")
                .with(jwt().jwt(j -> j
                    .claim("scope", "tenants.read")
                    .claim("roles", List.of("TENANT_READER")))
                  .authorities(new SimpleGrantedAuthority("SCOPE_tenants.read"),
                               new SimpleGrantedAuthority("ROLE_TENANT_READER"))))
           .andExpect(status().isOk());
    }

    @Test
    void getById_returns401_whenAnonymous() throws Exception {
        mvc.perform(get("/api/v1/tenants/test-id"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void getById_returns403_whenJwtMissingRole() throws Exception {
        mvc.perform(get("/api/v1/tenants/test-id")
                .with(jwt().jwt(j -> j
                    .claim("scope", "tenants.read")
                    .claim("roles", List.of()))
                  .authorities(new SimpleGrantedAuthority("SCOPE_tenants.read"))))
           .andExpect(status().isForbidden());
    }

    @Test
    void summary_returns429_after10Calls() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/v1/tenants/test-id/summary")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .with(jwt().jwt(j -> j
                        .subject("rate-limit-user")
                        .claim("scope", "tenants.read")
                        .claim("roles", List.of("TENANT_READER")))
                      .authorities(new SimpleGrantedAuthority("SCOPE_tenants.read"),
                                   new SimpleGrantedAuthority("ROLE_TENANT_READER"))))
               .andExpect(status().isOk());
        }
        mvc.perform(post("/api/v1/tenants/test-id/summary")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(jwt().jwt(j -> j
                    .subject("rate-limit-user")
                    .claim("scope", "tenants.read")
                    .claim("roles", List.of("TENANT_READER")))
                  .authorities(new SimpleGrantedAuthority("SCOPE_tenants.read"),
                               new SimpleGrantedAuthority("ROLE_TENANT_READER"))))
           .andExpect(status().isTooManyRequests())
           .andExpect(header().string("Retry-After", "60"));
    }
}
