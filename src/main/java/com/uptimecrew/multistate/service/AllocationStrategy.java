package com.uptimecrew.multistate.service;

import com.uptimecrew.multistate.model.IncomeAllocation;
import com.uptimecrew.multistate.model.WorkDay;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Splits a worker's total income across the jurisdictions where the work was performed.
 *
 * <p>Implementations differ in how they weight the split (day count, hours, revenue,
 * statutory formula) and how they handle rounding residuals. All implementations must
 * return one {@link IncomeAllocation} per distinct jurisdiction present in {@code workDays}
 * (or an empty list if {@code workDays} is empty).
 */
public interface AllocationStrategy {

    /**
     * @param workerId      ID of the worker whose income is being allocated
     * @param totalIncome   gross income to split; must be non-negative
     * @param workDays      the worker's days for the period — drives the split weights
     * @param allocatedFor  the period these allocations cover (e.g. pay period end date)
     * @return one allocation per jurisdiction touched in {@code workDays}; empty if none
     */
    List<IncomeAllocation> allocate(
        String workerId,
        BigDecimal totalIncome,
        List<WorkDay> workDays,
        LocalDate allocatedFor
    );
}
