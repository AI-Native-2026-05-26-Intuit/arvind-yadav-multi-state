package com.uptimecrew.multistate.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IncomeAllocationTest {

    @Test
    void constructs_with_valid_inputs() {
        IncomeAllocation subject = new IncomeAllocation(
            "alloc-001",
            "emp_42",
            "CA",
            new BigDecimal("12500.00"),
            LocalDate.of(2026, 3, 1)
        );
        assertEquals("alloc-001", subject.id());
        assertEquals("emp_42", subject.workerId());
        assertEquals("CA", subject.jurisdictionCode());
        assertEquals(0, new BigDecimal("12500.00").compareTo(subject.amount()));
        assertEquals(LocalDate.of(2026, 3, 1), subject.allocatedFor());
    }

    @Test
    void rejects_null_workerId() {
        assertThrows(NullPointerException.class, () -> new IncomeAllocation(
            "alloc-001",
            null,
            "CA",
            new BigDecimal("12500.00"),
            LocalDate.of(2026, 3, 1)
        ));
    }

    @Test
    void rejects_negative_amount() {
        assertThrows(IllegalArgumentException.class, () -> new IncomeAllocation(
            "alloc-001",
            "emp_42",
            "CA",
            new BigDecimal("-1.00"),
            LocalDate.of(2026, 3, 1)
        ));
    }
}
