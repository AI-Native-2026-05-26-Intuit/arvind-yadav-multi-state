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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DayCountAllocationStrategyTest {

    @Test
    void allocates_proportionally_to_workday_counts_per_jurisdiction() {
        AllocationStrategy strategy = new DayCountAllocationStrategy();
        LocalDate allocatedFor = LocalDate.of(2026, 3, 31);
        List<WorkDay> workDays = List.of(
            new WorkDay("wd-1", "emp_42", "CA", LocalDate.of(2026, 3, 1)),
            new WorkDay("wd-2", "emp_42", "CA", LocalDate.of(2026, 3, 2)),
            new WorkDay("wd-3", "emp_42", "CA", LocalDate.of(2026, 3, 3)),
            new WorkDay("wd-4", "emp_42", "NY", LocalDate.of(2026, 3, 4))
        );

        List<IncomeAllocation> result = strategy.allocate(
            "emp_42",
            new BigDecimal("12500.00"),
            workDays,
            allocatedFor
        );

        assertNotNull(result);
        assertEquals(2, result.size());
        Map<String, BigDecimal> byJurisdiction = result.stream()
            .collect(Collectors.toMap(IncomeAllocation::jurisdictionCode, IncomeAllocation::amount));
        assertEquals(0, new BigDecimal("9375.00").compareTo(byJurisdiction.get("CA")));
        assertEquals(0, new BigDecimal("3125.00").compareTo(byJurisdiction.get("NY")));
        assertTrue(result.stream().allMatch(a -> a.workerId().equals("emp_42")));
        assertTrue(result.stream().allMatch(a -> a.allocatedFor().equals(allocatedFor)));
    }
}
