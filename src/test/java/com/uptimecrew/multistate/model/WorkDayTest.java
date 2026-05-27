package com.uptimecrew.multistate.model;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkDayTest {

    @Test
    void constructs_with_valid_inputs() {
        WorkDay subject = new WorkDay(
            "wd-001",
            "emp_42",
            "CA",
            LocalDate.of(2026, 3, 1)
        );
        assertEquals("wd-001", subject.id());
        assertEquals("emp_42", subject.workerId());
        assertEquals("CA", subject.jurisdictionCode());
        assertEquals(LocalDate.of(2026, 3, 1), subject.workedOn());
    }

    @Test
    void rejects_null_workedOn() {
        assertThrows(NullPointerException.class, () -> new WorkDay(
            "wd-001",
            "emp_42",
            "CA",
            null
        ));
    }

    @Test
    void rejects_blank_jurisdictionCode() {
        assertThrows(IllegalArgumentException.class, () -> new WorkDay(
            "wd-001",
            "emp_42",
            "",
            LocalDate.of(2026, 3, 1)
        ));
    }
}
