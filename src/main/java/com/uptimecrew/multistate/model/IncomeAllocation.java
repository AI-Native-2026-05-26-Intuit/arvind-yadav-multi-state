package com.uptimecrew.multistate.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The result of running an {@link com.uptimecrew.multistate.service.AllocationStrategy}:
 * a portion of one worker's income assigned to one jurisdiction for one allocation period
 * (typically a pay period or filing period, identified by {@code allocatedFor}).
 *
 * <p>{@code amount} is always non-negative, scale 2, HALF_UP — the canonical money shape
 * for this project. A full allocation run produces one IncomeAllocation per jurisdiction
 * the worker touched during the period.
 */
public record IncomeAllocation(
    String id,
    String workerId,
    String jurisdictionCode,
    BigDecimal amount,
    LocalDate allocatedFor
) {

    public IncomeAllocation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(jurisdictionCode, "jurisdictionCode");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(allocatedFor, "allocatedFor");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        if (jurisdictionCode.isBlank()) {
            throw new IllegalArgumentException("jurisdictionCode must not be blank");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative: " + amount);
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
    }
}
