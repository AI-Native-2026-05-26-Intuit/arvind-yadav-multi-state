// path: src/main/java/com/uptimecrew/multistate/model/IncomeAllocationDraft.java
package com.uptimecrew.multistate.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Pre-persistence shape of an {@link IncomeAllocation} — same fields minus {@code workerId}.
 * Used while a batch of allocations is being assembled for a single worker: the worker is
 * known to the caller and is attached when drafts are promoted to {@code IncomeAllocation}.
 *
 * <p>Same money invariants as {@code IncomeAllocation}: non-negative, scale 2, HALF_UP.
 */
public final class IncomeAllocationDraft {

    private final String id;
    private final BigDecimal amount;
    private final String jurisdictionCode;
    private final LocalDate allocatedFor;

    public IncomeAllocationDraft(String id, BigDecimal amount, String jurisdictionCode, LocalDate allocatedFor) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(jurisdictionCode, "jurisdictionCode");
        Objects.requireNonNull(allocatedFor, "allocatedFor");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative: " + amount);
        }
        this.id = id;
        this.amount = amount.setScale(2, RoundingMode.HALF_UP);
        this.jurisdictionCode = jurisdictionCode;
        this.allocatedFor = allocatedFor;
    }

    public String getId() {
        return id;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getJurisdictionCode() {
        return jurisdictionCode;
    }

    public LocalDate getAllocatedFor() {
        return allocatedFor;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IncomeAllocationDraft other)) return false;
        return id.equals(other.id)
            && amount.equals(other.amount)
            && jurisdictionCode.equals(other.jurisdictionCode)
            && allocatedFor.equals(other.allocatedFor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, amount, jurisdictionCode, allocatedFor);
    }

    @Override
    public String toString() {
        return "IncomeAllocationDraft[id=" + id
            + ", jurisdictionCode=" + jurisdictionCode
            + ", amount=" + amount
            + ", allocatedFor=" + allocatedFor + "]";
    }
}
