package com.uptimecrew.multistate.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.uptimecrew.multistate.entity.Tenant;
import com.uptimecrew.multistate.model.WorkDay;
import com.uptimecrew.multistate.repository.TenantRepository;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AllocationServiceTest {

    private static final LocalDate PERIOD = LocalDate.of(2026, 3, 31);

    /** Mock repository whose save(...) echoes back the entity it was given. */
    private static TenantRepository savingRepo() {
        TenantRepository repo = mock(TenantRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return repo;
    }

    @Test
    void persists_tenant_with_primary_jurisdiction_from_largest_allocation() {
        AllocationService service = new AllocationService(new DayCountAllocationStrategy(), savingRepo());
        List<WorkDay> workDays = List.of(
            new WorkDay("wd-1", "emp_42", "CA", LocalDate.of(2026, 3, 1)),
            new WorkDay("wd-2", "emp_42", "CA", LocalDate.of(2026, 3, 2)),
            new WorkDay("wd-3", "emp_42", "CA", LocalDate.of(2026, 3, 3)),
            new WorkDay("wd-4", "emp_42", "NY", LocalDate.of(2026, 3, 4))
        );

        Tenant saved = service.allocate("emp_42", "Acme LLC", new BigDecimal("12500.00"), workDays, PERIOD);

        assertEquals("emp_42", saved.getId());
        assertEquals("Acme LLC", saved.getLegalName());
        assertEquals("CA", saved.getPrimaryJurisdictionCode());   // CA got 3 of 4 days → largest share
        assertEquals("ACTIVE", saved.getStatus());
        assertEquals(PERIOD, saved.getIncorporatedOn());
    }

    @Test
    void saves_the_built_entity_to_the_repository() {
        TenantRepository repo = savingRepo();
        AllocationService service = new AllocationService(new DayCountAllocationStrategy(), repo);

        Tenant saved = service.allocate(
            "emp_42",
            "Acme LLC",
            new BigDecimal("100.00"),
            List.of(new WorkDay("wd-1", "emp_42", "CA", LocalDate.of(2026, 3, 1))),
            PERIOD);

        verify(repo).save(saved);
    }

    @Test
    void rejects_null_constructor_args() {
        TenantRepository repo = mock(TenantRepository.class);
        assertThrows(NullPointerException.class, () -> new AllocationService(null, repo));
        assertThrows(NullPointerException.class,
            () -> new AllocationService(new DayCountAllocationStrategy(), null));
    }

    @Test
    void rejects_null_arguments() {
        AllocationService service = new AllocationService(new DayCountAllocationStrategy(), savingRepo());
        List<WorkDay> emptyDays = List.of();
        assertThrows(NullPointerException.class,
            () -> service.allocate(null, "Acme LLC", BigDecimal.ZERO, emptyDays, PERIOD));
        assertThrows(NullPointerException.class,
            () -> service.allocate("emp_42", null, BigDecimal.ZERO, emptyDays, PERIOD));
        assertThrows(NullPointerException.class,
            () -> service.allocate("emp_42", "Acme LLC", null, emptyDays, PERIOD));
        assertThrows(NullPointerException.class,
            () -> service.allocate("emp_42", "Acme LLC", BigDecimal.ZERO, null, PERIOD));
        assertThrows(NullPointerException.class,
            () -> service.allocate("emp_42", "Acme LLC", BigDecimal.ZERO, emptyDays, null));
    }

    @Test
    void rejects_negative_total_income() {
        AllocationService service = new AllocationService(new DayCountAllocationStrategy(), savingRepo());
        assertThrows(IllegalArgumentException.class,
            () -> service.allocate("emp_42", "Acme LLC", new BigDecimal("-1.00"), List.of(), PERIOD));
    }

    @Test
    void throws_when_no_allocations_to_derive_primary_jurisdiction() {
        AllocationService service = new AllocationService(new DayCountAllocationStrategy(), savingRepo());
        assertThrows(IllegalArgumentException.class,
            () -> service.allocate("emp_42", "Acme LLC", new BigDecimal("100.00"), List.of(), PERIOD));
    }
}
