// path: src/test/java/com/uptimecrew/multistate/model/IncomeAllocationDraftTest.java
package com.uptimecrew.multistate.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IncomeAllocationDraftTest {

    @Test
    void constructs_with_valid_inputs() {
        IncomeAllocationDraft subject = new IncomeAllocationDraft(
            "alloc-synth-001",
            new BigDecimal("12500.00"),
            "CA",
            LocalDate.of(2026, 3, 1)
        );
        assertEquals("alloc-synth-001", subject.getId());
        assertEquals(0, new BigDecimal("12500.00").compareTo(subject.getAmount()));
        assertEquals("CA", subject.getJurisdictionCode());
        assertEquals(LocalDate.of(2026, 3, 1), subject.getAllocatedFor());
    }

    @Test
    void rejects_null_jurisdictionCode() {
        assertThrows(NullPointerException.class, () -> new IncomeAllocationDraft(
            "alloc-synth-001",
            new BigDecimal("12500.00"),
            null,
            LocalDate.of(2026, 3, 1)
        ));
    }

    @ParameterizedTest(name = "rejects amount = {0}")
    @CsvSource({
        "-0.01",
        "-1.00",
        "-12500.00",
        "-100000.00"
    })
    void rejects_negative_amount(String amount) {
        assertThrows(IllegalArgumentException.class, () -> new IncomeAllocationDraft(
            "alloc-synth-001",
            new BigDecimal(amount),
            "CA",
            LocalDate.of(2026, 3, 1)
        ));
    }

    @Test
    void equal_instances_have_equal_hashcodes() {
        IncomeAllocationDraft a = new IncomeAllocationDraft(
            "alloc-synth-001", new BigDecimal("12500.00"), "CA", LocalDate.of(2026, 3, 1)
        );
        IncomeAllocationDraft b = new IncomeAllocationDraft(
            "alloc-synth-001", new BigDecimal("12500.00"), "CA", LocalDate.of(2026, 3, 1)
        );
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}