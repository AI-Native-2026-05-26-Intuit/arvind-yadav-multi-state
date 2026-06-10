package com.uptimecrew.multistate;

import static org.assertj.core.api.Assertions.assertThat;

import com.uptimecrew.multistate.entity.Tenant;
import com.uptimecrew.multistate.model.WorkDay;
import com.uptimecrew.multistate.service.AllocationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test (IT suffix) that boots a real Spring application context under the
 * {@code test} profile and exercises {@link AllocationService} through the injected bean
 * (not a hand-wired instance). The strategy that runs is whichever is marked {@code @Primary}
 * — currently {@code DayCountAllocationStrategy}, which splits income by day count.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApplicationContextLoadIT {

    @Autowired AllocationService service;

    @Test
    void context_loads_and_service_bean_is_wired() {
        assertThat(service)
            .as("Spring-managed AllocationService should be wired by the context")
            .isNotNull();
    }

    @Test
    void service_persists_tenant_with_primary_jurisdiction_from_largest_share() {
        // Arrange: two days in CA, one in NY — the @Primary day-count strategy
        // gives CA the larger share ($600 vs $300), so CA becomes the primary
        // jurisdiction on the persisted Tenant.
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
