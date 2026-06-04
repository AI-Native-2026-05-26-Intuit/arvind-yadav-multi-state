package com.uptimecrew.multistate.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.uptimecrew.multistate.model.IncomeAllocation;
import com.uptimecrew.multistate.model.WorkDay;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridAllocationStrategyTest {

    private static final LocalDate PERIOD = LocalDate.of(2026, 3, 31);
    private static final BigDecimal TOTAL = new BigDecimal("12500.00");

    private static List<WorkDay> threeDaysCaOneDayNy() {
        return List.of(
            new WorkDay("wd-1", "emp_42", "CA", LocalDate.of(2026, 3, 1)),
            new WorkDay("wd-2", "emp_42", "CA", LocalDate.of(2026, 3, 2)),
            new WorkDay("wd-3", "emp_42", "CA", LocalDate.of(2026, 3, 3)),
            new WorkDay("wd-4", "emp_42", "NY", LocalDate.of(2026, 3, 4))
        );
    }

    private static IncomeProportionalAllocationStrategy incomeSkewedToNy() {
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        weights.put("CA", new BigDecimal("1000"));
        weights.put("NY", new BigDecimal("3000"));
        return new IncomeProportionalAllocationStrategy(weights);
    }

    private static Map<String, BigDecimal> byJurisdiction(List<IncomeAllocation> allocations) {
        return allocations.stream()
            .collect(Collectors.toMap(IncomeAllocation::jurisdictionCode, IncomeAllocation::amount));
    }

    @Test
    void blends_primary_and_secondary_by_configured_weight() {
        AllocationStrategy hybrid = new HybridAllocationStrategy(
            new DayCountAllocationStrategy(),
            incomeSkewedToNy(),
            new BigDecimal("0.50"));

        List<IncomeAllocation> result =
            hybrid.allocate("emp_42", TOTAL, threeDaysCaOneDayNy(), PERIOD);

        Map<String, BigDecimal> by = byJurisdiction(result);
        assertEquals(2, result.size());
        assertEquals(0, new BigDecimal("6250.00").compareTo(by.get("CA")));
        assertEquals(0, new BigDecimal("6250.00").compareTo(by.get("NY")));
    }

    @Test
    void weight_of_one_equals_primary_alone() {
        DayCountAllocationStrategy day = new DayCountAllocationStrategy();
        AllocationStrategy hybrid = new HybridAllocationStrategy(
            day, incomeSkewedToNy(), BigDecimal.ONE);

        Map<String, BigDecimal> hy = byJurisdiction(
            hybrid.allocate("emp_42", TOTAL, threeDaysCaOneDayNy(), PERIOD));
        Map<String, BigDecimal> direct = byJurisdiction(
            day.allocate("emp_42", TOTAL, threeDaysCaOneDayNy(), PERIOD));

        assertEquals(direct.keySet(), hy.keySet());
        for (String code : direct.keySet()) {
            assertEquals(0, direct.get(code).compareTo(hy.get(code)));
        }
    }

    @Test
    void weight_of_zero_equals_secondary_alone() {
        IncomeProportionalAllocationStrategy income = incomeSkewedToNy();
        AllocationStrategy hybrid = new HybridAllocationStrategy(
            new DayCountAllocationStrategy(), income, BigDecimal.ZERO);

        Map<String, BigDecimal> hy = byJurisdiction(
            hybrid.allocate("emp_42", TOTAL, threeDaysCaOneDayNy(), PERIOD));
        Map<String, BigDecimal> direct = byJurisdiction(
            income.allocate("emp_42", TOTAL, threeDaysCaOneDayNy(), PERIOD));

        assertEquals(direct.keySet(), hy.keySet());
        for (String code : direct.keySet()) {
            assertEquals(0, direct.get(code).compareTo(hy.get(code)));
        }
    }

    @Test
    void returns_empty_list_when_workdays_empty() {
        AllocationStrategy hybrid = new HybridAllocationStrategy(
            new DayCountAllocationStrategy(),
            incomeSkewedToNy(),
            new BigDecimal("0.50"));
        assertTrue(hybrid.allocate("emp_42", TOTAL, List.of(), PERIOD).isEmpty());
    }

    @Test
    void rejects_null_constructor_args() {
        DayCountAllocationStrategy day = new DayCountAllocationStrategy();
        assertThrows(NullPointerException.class,
            () -> new HybridAllocationStrategy(null, day, BigDecimal.ZERO));
        assertThrows(NullPointerException.class,
            () -> new HybridAllocationStrategy(day, null, BigDecimal.ZERO));
        assertThrows(NullPointerException.class,
            () -> new HybridAllocationStrategy(day, day, null));
    }

    @Test
    void rejects_weight_outside_zero_one_inclusive() {
        DayCountAllocationStrategy day = new DayCountAllocationStrategy();
        assertThrows(IllegalArgumentException.class,
            () -> new HybridAllocationStrategy(day, day, new BigDecimal("-0.01")));
        assertThrows(IllegalArgumentException.class,
            () -> new HybridAllocationStrategy(day, day, new BigDecimal("1.01")));
    }

    @Test
    void rejects_negative_total_income() {
        AllocationStrategy hybrid = new HybridAllocationStrategy(
            new DayCountAllocationStrategy(),
            incomeSkewedToNy(),
            new BigDecimal("0.50"));
        assertThrows(IllegalArgumentException.class,
            () -> hybrid.allocate("emp_42", new BigDecimal("-1.00"),
                threeDaysCaOneDayNy(), PERIOD));
    }

    @Test
    void contract_jurisdictions_only_in_one_delegate_contribute_zero_from_the_other() {
        AllocationStrategy primary = new DayCountAllocationStrategy();
        AllocationStrategy secondary = (workerId, total, days, period) -> List.of(
            new IncomeAllocation(
                "fake-ca", workerId, "CA", new BigDecimal("12500.00"), period));

        AllocationStrategy hybrid = new HybridAllocationStrategy(
            primary, secondary, new BigDecimal("0.50"));

        Map<String, BigDecimal> by = byJurisdiction(
            hybrid.allocate("emp_42", TOTAL, threeDaysCaOneDayNy(), PERIOD));

        assertEquals(2, by.size());
        assertEquals(0, new BigDecimal("10937.50").compareTo(by.get("CA")));
        assertEquals(0, new BigDecimal("1562.50").compareTo(by.get("NY")));
    }

    @Test
    void equals_and_hashCode_reflect_primary_secondary_weight() {
        DayCountAllocationStrategy day = new DayCountAllocationStrategy();
        IncomeProportionalAllocationStrategy income1 = incomeSkewedToNy();
        IncomeProportionalAllocationStrategy income2 = incomeSkewedToNy();

        HybridAllocationStrategy h1 = new HybridAllocationStrategy(
            day, income1, new BigDecimal("0.50"));
        HybridAllocationStrategy h2 = new HybridAllocationStrategy(
            day, income2, new BigDecimal("0.50"));
        HybridAllocationStrategy h3 = new HybridAllocationStrategy(
            day, income1, new BigDecimal("0.75"));

        assertEquals(h1, h2);
        assertEquals(h1.hashCode(), h2.hashCode());
        assertTrue(!h1.equals(h3));
        assertTrue(h1.toString().contains("primaryWeight=0.50"));
    }
}
