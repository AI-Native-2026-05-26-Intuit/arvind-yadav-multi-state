package com.uptimecrew.multistate.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Fluent test data builder for {@link IncomeAllocation}. Lives in production so
 * tests in any module can use it. Defaults satisfy the record's compact-constructor
 * validation; override only the fields a test cares about.
 */
public final class IncomeAllocationTestDataBuilder {

    private String id = "alloc_default";
    private String workerId = "emp_default";
    private String jurisdictionCode = "CA";
    private BigDecimal amount = new BigDecimal("100.00");
    private LocalDate allocatedFor = LocalDate.of(2026, 1, 31);

    private IncomeAllocationTestDataBuilder() {
    }

    public static IncomeAllocationTestDataBuilder aIncomeAllocation() {
        return new IncomeAllocationTestDataBuilder();
    }

    public IncomeAllocationTestDataBuilder withId(String id) {
        this.id = id;
        return this;
    }

    public IncomeAllocationTestDataBuilder withWorkerId(String workerId) {
        this.workerId = workerId;
        return this;
    }

    public IncomeAllocationTestDataBuilder withJurisdictionCode(String jurisdictionCode) {
        this.jurisdictionCode = jurisdictionCode;
        return this;
    }

    public IncomeAllocationTestDataBuilder withAmount(BigDecimal amount) {
        this.amount = amount;
        return this;
    }

    public IncomeAllocationTestDataBuilder withAllocatedFor(LocalDate allocatedFor) {
        this.allocatedFor = allocatedFor;
        return this;
    }

    public IncomeAllocation build() {
        return new IncomeAllocation(id, workerId, jurisdictionCode, amount, allocatedFor);
    }
}
