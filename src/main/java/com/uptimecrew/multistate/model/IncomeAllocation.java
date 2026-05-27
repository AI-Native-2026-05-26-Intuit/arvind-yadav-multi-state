package com.uptimecrew.multistate.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

public final class IncomeAllocation {

    private final String id;
    private final String workerId;
    private final String jurisdictionCode;
    private final BigDecimal amount;
    private final LocalDate allocatedFor;

    public IncomeAllocation(
        String id,
        String workerId,
        String jurisdictionCode,
        BigDecimal amount,
        LocalDate allocatedFor
    ) {
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
        this.id = id;
        this.workerId = workerId;
        this.jurisdictionCode = jurisdictionCode;
        this.amount = amount.setScale(2, RoundingMode.HALF_UP);
        this.allocatedFor = allocatedFor;
    }

    public String id() {
        return id;
    }

    public String workerId() {
        return workerId;
    }

    public String jurisdictionCode() {
        return jurisdictionCode;
    }

    public BigDecimal amount() {
        return amount;
    }

    public LocalDate allocatedFor() {
        return allocatedFor;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IncomeAllocation other)) return false;
        return id.equals(other.id)
            && workerId.equals(other.workerId)
            && jurisdictionCode.equals(other.jurisdictionCode)
            && amount.equals(other.amount)
            && allocatedFor.equals(other.allocatedFor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, workerId, jurisdictionCode, amount, allocatedFor);
    }

    @Override
    public String toString() {
        return "IncomeAllocation[id=" + id
            + ", workerId=" + workerId
            + ", jurisdictionCode=" + jurisdictionCode
            + ", amount=" + amount
            + ", allocatedFor=" + allocatedFor + "]";
    }
}
