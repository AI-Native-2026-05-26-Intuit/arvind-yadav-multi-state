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

    @Test
    void rejects_null_id() {
        assertThrows(NullPointerException.class, () -> new WorkDay(
            null, "emp_42", "CA", LocalDate.of(2026, 3, 1)));
    }

    @Test
    void rejects_null_workerId() {
        assertThrows(NullPointerException.class, () -> new WorkDay(
            "wd-001", null, "CA", LocalDate.of(2026, 3, 1)));
    }

    @Test
    void rejects_null_jurisdictionCode() {
        assertThrows(NullPointerException.class, () -> new WorkDay(
            "wd-001", "emp_42", null, LocalDate.of(2026, 3, 1)));
    }

    @Test
    void rejects_blank_id() {
        assertThrows(IllegalArgumentException.class, () -> new WorkDay(
            "", "emp_42", "CA", LocalDate.of(2026, 3, 1)));
    }

    @Test
    void rejects_blank_workerId() {
        assertThrows(IllegalArgumentException.class, () -> new WorkDay(
            "wd-001", "", "CA", LocalDate.of(2026, 3, 1)));
    }

    @Test
    void equals_and_hashCode_cover_branches() {
        LocalDate date = LocalDate.of(2026, 3, 1);
        WorkDay a = new WorkDay("wd-001", "emp_42", "CA", date);
        WorkDay same = new WorkDay("wd-001", "emp_42", "CA", date);
        WorkDay diffId = new WorkDay("wd-002", "emp_42", "CA", date);
        WorkDay diffWorker = new WorkDay("wd-001", "emp_43", "CA", date);
        WorkDay diffCode = new WorkDay("wd-001", "emp_42", "NY", date);
        WorkDay diffDate = new WorkDay("wd-001", "emp_42", "CA", date.plusDays(1));

        assertEquals(a, a);
        assertEquals(a, same);
        assertEquals(a.hashCode(), same.hashCode());
        assertEquals(false, a.equals(null));
        assertEquals(false, a.equals("not a workday"));
        assertEquals(false, a.equals(diffId));
        assertEquals(false, a.equals(diffWorker));
        assertEquals(false, a.equals(diffCode));
        assertEquals(false, a.equals(diffDate));
    }

    @Test
    void toString_includes_fields() {
        WorkDay subject = new WorkDay("wd-001", "emp_42", "CA", LocalDate.of(2026, 3, 1));
        assertEquals(true, subject.toString().contains("wd-001"));
    }
}
