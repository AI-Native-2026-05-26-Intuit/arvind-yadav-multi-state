package com.uptimecrew.multistate.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.uptimecrew.multistate.model.IncomeAllocation;
import com.uptimecrew.multistate.model.WorkDay;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllocationServiceTest {

    private static final LocalDate PERIOD = LocalDate.of(2026, 3, 31);

    @Test
    void delegates_to_injected_strategy() {
        AllocationService service = new AllocationService(new DayCountAllocationStrategy());
        List<WorkDay> workDays = List.of(
            new WorkDay("wd-1", "emp_42", "CA", LocalDate.of(2026, 3, 1)),
            new WorkDay("wd-2", "emp_42", "CA", LocalDate.of(2026, 3, 2)),
            new WorkDay("wd-3", "emp_42", "CA", LocalDate.of(2026, 3, 3)),
            new WorkDay("wd-4", "emp_42", "NY", LocalDate.of(2026, 3, 4))
        );

        List<IncomeAllocation> result =
            service.allocate("emp_42", new BigDecimal("12500.00"), workDays, PERIOD);

        Map<String, BigDecimal> byJurisdiction = result.stream()
            .collect(Collectors.toMap(IncomeAllocation::jurisdictionCode, IncomeAllocation::amount));
        assertEquals(2, result.size());
        assertEquals(0, new BigDecimal("9375.00").compareTo(byJurisdiction.get("CA")));
        assertEquals(0, new BigDecimal("3125.00").compareTo(byJurisdiction.get("NY")));
    }

    @Test
    void returned_list_is_unmodifiable() {
        AllocationService service = new AllocationService(new DayCountAllocationStrategy());
        List<IncomeAllocation> result = service.allocate(
            "emp_42",
            new BigDecimal("100.00"),
            List.of(new WorkDay("wd-1", "emp_42", "CA", LocalDate.of(2026, 3, 1))),
            PERIOD);
        assertThrows(UnsupportedOperationException.class, () -> result.add(null));
    }

    @Test
    void rejects_null_strategy_at_construction() {
        assertThrows(NullPointerException.class, () -> new AllocationService(null));
    }

    @Test
    void rejects_null_arguments() {
        AllocationService service = new AllocationService(new DayCountAllocationStrategy());
        List<WorkDay> emptyDays = List.of();
        assertThrows(NullPointerException.class,
            () -> service.allocate(null, BigDecimal.ZERO, emptyDays, PERIOD));
        assertThrows(NullPointerException.class,
            () -> service.allocate("emp_42", null, emptyDays, PERIOD));
        assertThrows(NullPointerException.class,
            () -> service.allocate("emp_42", BigDecimal.ZERO, null, PERIOD));
        assertThrows(NullPointerException.class,
            () -> service.allocate("emp_42", BigDecimal.ZERO, emptyDays, null));
    }

    @Test
    void rejects_negative_total_income() {
        AllocationService service = new AllocationService(new DayCountAllocationStrategy());
        assertThrows(IllegalArgumentException.class,
            () -> service.allocate("emp_42", new BigDecimal("-1.00"), List.of(), PERIOD));
    }

    @Test
    void empty_workdays_yields_empty_result() {
        AllocationService service = new AllocationService(new DayCountAllocationStrategy());
        List<IncomeAllocation> result =
            service.allocate("emp_42", new BigDecimal("100.00"), List.of(), PERIOD);
        assertTrue(result.isEmpty());
    }
}
