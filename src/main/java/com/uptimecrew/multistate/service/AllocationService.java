package com.uptimecrew.multistate.service;

import com.uptimecrew.multistate.model.IncomeAllocation;
import com.uptimecrew.multistate.model.WorkDay;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Domain service that runs an injected {@link AllocationStrategy} against a worker's
 * inputs. The strategy is provided at construction (never created here), letting callers
 * swap allocation behaviour without changing this service.
 *
 * <p>Validates inputs at the service boundary, delegates the split to the strategy, and
 * returns the result as an unmodifiable list so downstream code cannot mutate it.
 */
public final class AllocationService {

    private final AllocationStrategy strategy;

    public AllocationService(AllocationStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
    }

    public List<IncomeAllocation> allocate(
        String workerId,
        BigDecimal totalIncome,
        List<WorkDay> workDays,
        LocalDate allocatedFor
    ) {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(totalIncome, "totalIncome");
        Objects.requireNonNull(workDays, "workDays");
        Objects.requireNonNull(allocatedFor, "allocatedFor");
        if (totalIncome.signum() < 0) {
            throw new IllegalArgumentException("totalIncome must not be negative: " + totalIncome);
        }

        List<IncomeAllocation> result =
            strategy.allocate(workerId, totalIncome, workDays, allocatedFor);
        return List.copyOf(result);
    }
}
