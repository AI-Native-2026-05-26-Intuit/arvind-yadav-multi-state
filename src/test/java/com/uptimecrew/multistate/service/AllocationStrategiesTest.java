package com.uptimecrew.multistate.service;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllocationStrategiesTest {

    @Test
    void byDayCount_returns_an_AllocationStrategy_backed_by_DayCount() {
        AllocationStrategy s = AllocationStrategies.byDayCount();
        assertInstanceOf(DayCountAllocationStrategy.class, s);
    }

    @Test
    void byIncomeProportion_returns_an_AllocationStrategy_backed_by_IncomeProportional() {
        AllocationStrategy s = AllocationStrategies.byIncomeProportion(
            Map.of("CA", new BigDecimal("100")));
        assertInstanceOf(IncomeProportionalAllocationStrategy.class, s);
    }

    @Test
    void byHybridBlend_returns_an_AllocationStrategy_backed_by_Hybrid() {
        AllocationStrategy s = AllocationStrategies.byHybridBlend(
            AllocationStrategies.byDayCount(),
            AllocationStrategies.byIncomeProportion(Map.of("CA", new BigDecimal("100"))),
            new BigDecimal("0.50"));
        assertInstanceOf(HybridAllocationStrategy.class, s);
    }

    @Test
    void constructor_is_not_invocable_reflectively() throws Exception {
        Constructor<AllocationStrategies> ctor =
            AllocationStrategies.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        InvocationTargetException wrapper = assertThrows(
            InvocationTargetException.class, ctor::newInstance);
        Throwable cause = wrapper.getCause();
        assertTrue(cause instanceof AssertionError,
            "expected AssertionError cause, got " + cause);
        assertEquals("AllocationStrategies is not instantiable", cause.getMessage());
    }

    @Test
    void byIncomeProportion_propagates_null_validation_from_underlying_constructor() {
        assertThrows(NullPointerException.class,
            () -> AllocationStrategies.byIncomeProportion(null));
    }

    @Test
    void byHybridBlend_propagates_weight_validation_from_underlying_constructor() {
        AllocationStrategy day = AllocationStrategies.byDayCount();
        assertThrows(IllegalArgumentException.class,
            () -> AllocationStrategies.byHybridBlend(day, day, new BigDecimal("1.5")));
    }
}
