package com.uptimecrew.multistate.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.uptimecrew.multistate.model.IncomeAllocation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllocationRegistryTest {

    private static IncomeAllocation allocation(
        String id,
        String jurisdiction,
        String amount,
        LocalDate allocatedFor
    ) {
        return new IncomeAllocation(id, "emp_1", jurisdiction, new BigDecimal(amount), allocatedFor);
    }

    @Test
    void collection_constructor_defensively_copies_input() {
        IncomeAllocation a = allocation("alloc_1", "CA", "100.00", LocalDate.of(2026, 3, 1));
        IncomeAllocation b = allocation("alloc_2", "CA", "200.00", LocalDate.of(2026, 3, 2));
        List<IncomeAllocation> input = new ArrayList<>(List.of(a, b));

        AllocationRegistry registry = new AllocationRegistry(input);
        input.clear();
        input.add(allocation("alloc_3", "CA", "300.00", LocalDate.of(2026, 3, 3)));

        assertEquals(2, registry.size());
        assertTrue(registry.findById("alloc_1").isPresent());
        assertTrue(registry.findById("alloc_2").isPresent());
        assertFalse(registry.findById("alloc_3").isPresent());
    }

    @Test
    void map_constructor_defensively_copies_input() {
        IncomeAllocation a = allocation("alloc_1", "CA", "100.00", LocalDate.of(2026, 3, 1));
        IncomeAllocation b = allocation("alloc_2", "CA", "200.00", LocalDate.of(2026, 3, 2));
        Map<String, IncomeAllocation> input = new HashMap<>();
        input.put(a.id(), a);
        input.put(b.id(), b);

        AllocationRegistry registry = new AllocationRegistry(input);
        input.clear();

        assertEquals(2, registry.size());
        assertTrue(registry.findById("alloc_1").isPresent());
        assertTrue(registry.findById("alloc_2").isPresent());
    }

    @Test
    void findById_returns_allocation_when_present() {
        IncomeAllocation a = allocation("alloc_1", "CA", "100.00", LocalDate.of(2026, 3, 1));
        AllocationRegistry registry = new AllocationRegistry(List.of(a));

        Optional<IncomeAllocation> result = registry.findById("alloc_1");

        assertTrue(result.isPresent());
        assertEquals(a, result.get());
    }

    @Test
    void findById_returns_empty_when_missing() {
        IncomeAllocation a = allocation("alloc_1", "CA", "100.00", LocalDate.of(2026, 3, 1));
        AllocationRegistry registry = new AllocationRegistry(List.of(a));

        assertTrue(registry.findById("alloc_unknown").isEmpty());
    }

    @Test
    void findById_throws_on_null_id() {
        AllocationRegistry registry = new AllocationRegistry(List.<IncomeAllocation>of());
        assertThrows(NullPointerException.class, () -> registry.findById(null));
    }

    @Test
    void collection_constructor_rejects_duplicate_ids() {
        IncomeAllocation a = allocation("alloc_1", "CA", "100.00", LocalDate.of(2026, 3, 1));
        IncomeAllocation dup = allocation("alloc_1", "NY", "50.00", LocalDate.of(2026, 3, 2));

        assertThrows(IllegalArgumentException.class,
            () -> new AllocationRegistry(List.of(a, dup)));
    }

    @Test
    void findByJurisdictionAbove_sortsByAmountDescThenDateAscThenIdAsc() {
        // Two share amount 500 (date tiebreak); two share amount+date 300/2026-03-05 (id tiebreak).
        IncomeAllocation top = allocation("alloc_top", "CA", "900.00", LocalDate.of(2026, 3, 10));
        IncomeAllocation midEarlier = allocation("alloc_mid_b", "CA", "500.00", LocalDate.of(2026, 3, 1));
        IncomeAllocation midLater = allocation("alloc_mid_a", "CA", "500.00", LocalDate.of(2026, 3, 9));
        IncomeAllocation lowIdA = allocation("alloc_a", "CA", "300.00", LocalDate.of(2026, 3, 5));
        IncomeAllocation lowIdB = allocation("alloc_b", "CA", "300.00", LocalDate.of(2026, 3, 5));

        AllocationRegistry registry = new AllocationRegistry(
            List.of(lowIdB, midLater, top, lowIdA, midEarlier));

        List<IncomeAllocation> result = registry.findByJurisdictionAbove("CA", new BigDecimal("100.00"));

        assertEquals(List.of(top, midEarlier, midLater, lowIdA, lowIdB), result);
    }

    @Test
    void findByJurisdictionAbove_filtersOutOtherJurisdictions() {
        IncomeAllocation ca = allocation("alloc_1", "CA", "500.00", LocalDate.of(2026, 3, 1));
        IncomeAllocation ny = allocation("alloc_2", "NY", "900.00", LocalDate.of(2026, 3, 1));
        AllocationRegistry registry = new AllocationRegistry(List.of(ca, ny));

        List<IncomeAllocation> result = registry.findByJurisdictionAbove("CA", new BigDecimal("100.00"));

        assertEquals(List.of(ca), result);
    }

    @Test
    void findByJurisdictionAbove_excludesAmountsEqualToThreshold() {
        IncomeAllocation exactlyThreshold = allocation("alloc_1", "CA", "100.00", LocalDate.of(2026, 3, 1));
        IncomeAllocation above = allocation("alloc_2", "CA", "100.01", LocalDate.of(2026, 3, 2));
        AllocationRegistry registry = new AllocationRegistry(List.of(exactlyThreshold, above));

        List<IncomeAllocation> result = registry.findByJurisdictionAbove("CA", new BigDecimal("100.00"));

        assertEquals(List.of(above), result);
    }
}
