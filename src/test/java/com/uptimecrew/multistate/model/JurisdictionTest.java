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
}
