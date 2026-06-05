package com.uptimecrew.multistate.exception;

/**
 * Root of the multistate domain exception hierarchy. Abstract so callers must
 * throw one of the concrete subclasses, not the base.
 *
 * Unchecked (extends RuntimeException) because all multistate failure modes are
 * programmer errors at the call site or transient upstream failures the caller
 * cannot reasonably handle synchronously — logging + retry at the
 * service boundary is the recovery model.
 */
public abstract class AllocationException extends RuntimeException {

    protected AllocationException(String message) {
        super(message);
    }

    protected AllocationException(String message, Throwable cause) {
        super(message, cause);
    }
}
