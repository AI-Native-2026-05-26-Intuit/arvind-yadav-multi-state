package com.uptimecrew.multistate.exception;

public final class IncomeAllocationFailedException extends AllocationException {

    public IncomeAllocationFailedException(String message) {
        super(message);
    }

    public IncomeAllocationFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
