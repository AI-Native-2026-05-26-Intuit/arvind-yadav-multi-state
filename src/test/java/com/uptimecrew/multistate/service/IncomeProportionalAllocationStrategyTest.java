package com.uptimecrew.multistate.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
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

class IncomeProportionalAllocationStrategyTest {

    private static final LocalDate PERIOD = LocalDate.of(2026, 3, 31);

    @Test
    void splits_total_income_in_proportion_to_configured_revenue_weights() {
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        weights.put("CA", new BigDecimal("3000"));
        weights.put("NY", new BigDecimal("1000"));
        AllocationStrategy strategy = new IncomeProportionalAllocationStrategy(weights);

        List<WorkDay> workDays = List.of(
            new WorkDay("wd-1", "emp_42", "CA", LocalDate.of(2026, 3, 1)),
            new WorkDay("wd-2", "emp_42", "NY", LocalDate.of(2026, 3, 2))
        );

        List<IncomeAllocation> result =
            strategy.allocate("emp_42", new BigDecimal("12500.00"), workDays, PERIOD);

        assertEquals(2, result.size());
        Map<String, BigDecimal> byJurisdiction = result.stream()
            .collect(Collectors.toMap(IncomeAllocation::jurisdictionCode, IncomeAllocation::amount));
        assertEquals(0, new BigDecimal("9375.00").compareTo(byJurisdiction.get("CA")));
        assertEquals(0, new BigDecimal("3125.00").compareTo(byJurisdiction.get("NY")));
    }

    @Test
    void omits_jurisdictions_with_no_workdays_even_if_configured() {
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        weights.put("CA", new BigDecimal("3000"));
        weights.put("NY", new BigDecimal("1000"));
        weights.put("TX", new BigDecimal("5000"));
        AllocationStrategy strategy = new IncomeProportionalAllocationStrategy(weights);

        List<WorkDay> workDays = List.of(
            new WorkDay("wd-1", "emp_42", "CA", LocalDate.of(2026, 3, 1)),
            new WorkDay("wd-2", "emp_42", "NY", LocalDate.of(2026, 3, 2))
        );

        List<IncomeAllocation> result =
            strategy.allocate("emp_42", new BigDecimal("4000.00"), workDays, PERIOD);

        assertEquals(2, result.size());
        Map<String, BigDecimal> byJurisdiction = result.stream()
            .collect(Collectors.toMap(IncomeAllocation::jurisdictionCode, IncomeAllocation::amount));
        assertEquals(0, new BigDecimal("3000.00").compareTo(byJurisdiction.get("CA")));
        assertEquals(0, new BigDecimal("1000.00").compareTo(byJurisdiction.get("NY")));
    }

    @Test
    void throws_when_workdays_reference_unconfigured_jurisdiction() {
        AllocationStrategy strategy = new IncomeProportionalAllocationStrategy(
            Map.of("CA", new BigDecimal("100")));
        List<WorkDay> workDays = List.of(
            new WorkDay("wd-1", "emp_42", "OR", LocalDate.of(2026, 3, 1))
        );
        assertThrows(IllegalArgumentException.class, () ->
            strategy.allocate("emp_42", new BigDecimal("100.00"), workDays, PERIOD));
    }

    @Test
    void returns_empty_list_when_workdays_empty() {
        AllocationStrategy strategy = new IncomeProportionalAllocationStrategy(
            Map.of("CA", new BigDecimal("100")));
        List<IncomeAllocation> result =
            strategy.allocate("emp_42", new BigDecimal("100.00"), List.of(), PERIOD);
        assertTrue(result.isEmpty());
    }

    @Test
    void rejects_negative_total_income() {
        AllocationStrategy strategy = new IncomeProportionalAllocationStrategy(
            Map.of("CA", new BigDecimal("100")));
        List<WorkDay> workDays = List.of(
            new WorkDay("wd-1", "emp_42", "CA", LocalDate.of(2026, 3, 1)));
        assertThrows(IllegalArgumentException.class, () ->
            strategy.allocate("emp_42", new BigDecimal("-1.00"), workDays, PERIOD));
    }

    @Test
    void rejects_null_constructor_arg() {
        assertThrows(NullPointerException.class,
            () -> new IncomeProportionalAllocationStrategy(null));
    }

    @Test
    void rejects_empty_constructor_map() {
        assertThrows(IllegalArgumentException.class,
            () -> new IncomeProportionalAllocationStrategy(Map.of()));
    }

    @Test
    void rejects_negative_weight_value() {
        Map<String, BigDecimal> weights = new HashMap<>();
        weights.put("CA", new BigDecimal("-1"));
        assertThrows(IllegalArgumentException.class,
            () -> new IncomeProportionalAllocationStrategy(weights));
    }

    @Test
    void rejects_blank_jurisdiction_code() {
        assertThrows(IllegalArgumentException.class,
            () -> new IncomeProportionalAllocationStrategy(Map.of("", new BigDecimal("100"))));
    }

    @Test
    void throws_when_all_participating_weights_are_zero() {
        AllocationStrategy strategy = new IncomeProportionalAllocationStrategy(
            Map.of("CA", BigDecimal.ZERO, "NY", BigDecimal.ZERO));
        List<WorkDay> workDays = List.of(
            new WorkDay("wd-1", "emp_42", "CA", LocalDate.of(2026, 3, 1)));
        assertThrows(IllegalArgumentException.class, () ->
            strategy.allocate("emp_42", new BigDecimal("100.00"), workDays, PERIOD));
    }

    @Test
    void equals_and_hashCode_reflect_income_map_contents() {
        Map<String, BigDecimal> a = Map.of("CA", new BigDecimal("100.00"));
        Map<String, BigDecimal> b = Map.of("CA", new BigDecimal("100.00"));
        Map<String, BigDecimal> c = Map.of("CA", new BigDecimal("200.00"));

        IncomeProportionalAllocationStrategy s1 = new IncomeProportionalAllocationStrategy(a);
        IncomeProportionalAllocationStrategy s2 = new IncomeProportionalAllocationStrategy(b);
        IncomeProportionalAllocationStrategy s3 = new IncomeProportionalAllocationStrategy(c);

        assertEquals(s1, s2);
        assertEquals(s1.hashCode(), s2.hashCode());
        assertTrue(!s1.equals(s3));
        assertTrue(s1.toString().contains("CA"));
    }
}
