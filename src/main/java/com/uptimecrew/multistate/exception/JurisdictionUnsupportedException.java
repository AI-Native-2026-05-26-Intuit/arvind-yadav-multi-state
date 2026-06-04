package com.uptimecrew.multistate.exception;

public final class JurisdictionUnsupportedException extends AllocationException {

    public JurisdictionUnsupportedException(String message) {
        super(message);
    }

    public JurisdictionUnsupportedException(String message, Throwable cause) {
        super(message, cause);
    }
}
