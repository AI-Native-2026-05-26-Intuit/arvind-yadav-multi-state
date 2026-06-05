package com.uptimecrew.multistate.service;

import com.uptimecrew.multistate.exception.JurisdictionUnsupportedException;
import com.uptimecrew.multistate.model.IncomeAllocation;
import com.uptimecrew.multistate.model.WorkDay;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Day 1 strategy: split totalIncome across jurisdictions in proportion to the number of
 * WorkDays in each, rounding every share with HALF_UP. Naive — shares may not sum to
 * totalIncome to the penny. Day 3 introduces strategies that handle residuals correctly.
 */
public final class DayCountAllocationStrategy implements AllocationStrategy {

    @Override
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
            throw new JurisdictionUnsupportedException(
                "DayCountAllocationStrategy cannot allocate negative totalIncome: "
                    + totalIncome + " for worker " + workerId);
        }
        if (workDays.isEmpty()) {
            return List.of();
        }

        Map<String, Long> daysByJurisdiction = new LinkedHashMap<>();
        for (WorkDay day : workDays) {
            daysByJurisdiction.merge(day.jurisdictionCode(), 1L, Long::sum);
        }

        BigDecimal totalDays = BigDecimal.valueOf(workDays.size());
        List<IncomeAllocation> allocations = new ArrayList<>(daysByJurisdiction.size());
        for (Map.Entry<String, Long> entry : daysByJurisdiction.entrySet()) {
            BigDecimal share = totalIncome
                .multiply(BigDecimal.valueOf(entry.getValue()))
                .divide(totalDays, 2, RoundingMode.HALF_UP);
            allocations.add(new IncomeAllocation(
                UUID.randomUUID().toString(),
                workerId,
                entry.getKey(),
                share,
                allocatedFor
            ));
        }
        return List.copyOf(allocations);
    }
}
