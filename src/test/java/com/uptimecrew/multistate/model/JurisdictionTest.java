package com.uptimecrew.multistate.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JurisdictionTest {

    @Test
    void constructs_with_valid_inputs() {
        Jurisdiction subject = new Jurisdiction("CA", "California", JurisdictionKind.STATE);
        assertEquals("CA", subject.code());
        assertEquals("California", subject.displayName());
        assertEquals(JurisdictionKind.STATE, subject.kind());
    }

    @Test
    void rejects_null_kind() {
        assertThrows(NullPointerException.class,
            () -> new Jurisdiction("CA", "California", null));
    }

    @Test
    void rejects_blank_code() {
        assertThrows(IllegalArgumentException.class,
            () -> new Jurisdiction("", "California", JurisdictionKind.STATE));
    }

    @Test
    void rejects_null_code() {
        assertThrows(NullPointerException.class,
            () -> new Jurisdiction(null, "California", JurisdictionKind.STATE));
    }

    @Test
    void rejects_null_displayName() {
        assertThrows(NullPointerException.class,
            () -> new Jurisdiction("CA", null, JurisdictionKind.STATE));
    }

    @Test
    void rejects_blank_displayName() {
        assertThrows(IllegalArgumentException.class,
            () -> new Jurisdiction("CA", "", JurisdictionKind.STATE));
    }

    @Test
    void equals_and_hashCode_cover_branches() {
        Jurisdiction a = new Jurisdiction("CA", "California", JurisdictionKind.STATE);
        Jurisdiction same = new Jurisdiction("CA", "California", JurisdictionKind.STATE);
        Jurisdiction diffCode = new Jurisdiction("NY", "California", JurisdictionKind.STATE);
        Jurisdiction diffName = new Jurisdiction("CA", "Cali", JurisdictionKind.STATE);

        assertEquals(a, a);
        assertEquals(a, same);
        assertEquals(a.hashCode(), same.hashCode());
        assertEquals(false, a.equals(null));
        assertEquals(false, a.equals("not a jurisdiction"));
        assertEquals(false, a.equals(diffCode));
        assertEquals(false, a.equals(diffName));
    }

    @Test
    void toString_includes_fields() {
        Jurisdiction subject = new Jurisdiction("CA", "California", JurisdictionKind.STATE);
        assertEquals(true, subject.toString().contains("CA"));
    }
}
