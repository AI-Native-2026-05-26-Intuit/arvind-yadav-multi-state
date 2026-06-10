package com.uptimecrew.multistate.service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Factory for {@link AllocationStrategy} implementations. Each factory method names a
 * behavioural variant (not its class) and returns the interface type, so callers do not
 * couple to a concrete implementation. Not instantiable — the private constructor throws
 * {@link AssertionError} to defeat reflective instantiation.
 *
 * <p>Retained after the Spring migration: Spring is the factory for the default
 * {@code @Primary} strategy injected into {@link AllocationService}, but this class still
 * serves callers OUTSIDE the Spring context — unit tests that don't boot Spring, and the
 * parameterized variants ({@link #byIncomeProportion} needs a revenue map,
 * {@link #byHybridBlend} needs two delegates and a weight) that Spring cannot construct
 * from component scanning alone.
 */
public final class AllocationStrategies {

    private AllocationStrategies() {
        throw new AssertionError("AllocationStrategies is not instantiable");
    }

    /**
     * Day 1 variant: split totalIncome across jurisdictions in proportion to the number of
     * work days in each.
     */
    public static AllocationStrategy byDayCount() {
        return new DayCountAllocationStrategy();
    }

    /**
     * Revenue-weighted variant: split totalIncome across jurisdictions in proportion to a
     * configured per-jurisdiction income map.
     */
    public static AllocationStrategy byIncomeProportion(
        Map<String, BigDecimal> incomeByJurisdiction
    ) {
        return new IncomeProportionalAllocationStrategy(incomeByJurisdiction);
    }

    /**
     * Hybrid variant: blend two delegate strategies by {@code primaryWeight} (in [0, 1]).
     * Each jurisdiction's blended share is {@code primaryWeight * primary + (1 - primaryWeight) * secondary}.
     */
    public static AllocationStrategy byHybridBlend(
        AllocationStrategy primary,
        AllocationStrategy secondary,
        BigDecimal primaryWeight
    ) {
        return new HybridAllocationStrategy(primary, secondary, primaryWeight);
    }
}
