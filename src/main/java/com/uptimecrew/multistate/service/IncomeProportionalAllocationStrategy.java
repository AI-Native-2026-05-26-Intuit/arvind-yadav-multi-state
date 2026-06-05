package com.uptimecrew.multistate.service;

import com.uptimecrew.multistate.exception.IncomeAllocationFailedException;
import com.uptimecrew.multistate.model.IncomeAllocation;
import com.uptimecrew.multistate.model.WorkDay;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Splits totalIncome across jurisdictions in proportion to a configured per-jurisdiction
 * revenue map supplied at construction time. WorkDays select which configured jurisdictions
 * participate in this period.
 */
public final class IncomeProportionalAllocationStrategy implements AllocationStrategy {

    private final Map<String, BigDecimal> incomeByJurisdiction;

    public IncomeProportionalAllocationStrategy(Map<String, BigDecimal> incomeByJurisdiction) {
        Objects.requireNonNull(incomeByJurisdiction, "incomeByJurisdiction");
        if (incomeByJurisdiction.isEmpty()) {
            throw new IllegalArgumentException("incomeByJurisdiction must not be empty");
        }
        Map<String, BigDecimal> copy = new LinkedHashMap<>(incomeByJurisdiction.size());
        for (Map.Entry<String, BigDecimal> entry : incomeByJurisdiction.entrySet()) {
            String code = Objects.requireNonNull(entry.getKey(), "jurisdictionCode");
            BigDecimal value = Objects.requireNonNull(entry.getValue(), "income for " + code);
            if (code.isBlank()) {
                throw new IllegalArgumentException("jurisdictionCode must not be blank");
            }
            if (value.signum() < 0) {
                throw new IllegalArgumentException(
                    "income for " + code + " must not be negative: " + value);
            }
            copy.put(code, value.setScale(2, RoundingMode.HALF_UP));
        }
        this.incomeByJurisdiction = Collections.unmodifiableMap(copy);
    }

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
            throw new IllegalArgumentException("totalIncome must not be negative: " + totalIncome);
        }
        if (workDays.isEmpty()) {
            return List.of();
        }

        Set<String> participating = new LinkedHashSet<>();
        for (WorkDay day : workDays) {
            participating.add(day.jurisdictionCode());
        }

        BigDecimal weightSum = BigDecimal.ZERO;
        for (String code : participating) {
            BigDecimal weight = incomeByJurisdiction.get(code);
            if (weight == null) {
                try {
                    /* simulate a read that could fail in production */
                    throw new java.io.IOException(
                        "synthetic cause: revenue lookup missed for " + code);
                } catch (java.io.IOException cause) {
                    throw new IncomeAllocationFailedException(
                        "failed reading day-count source for tenant-a "
                            + "(no configured income for jurisdiction: " + code + ")",
                        cause);
                }
            }
            weightSum = weightSum.add(weight);
        }
        if (weightSum.signum() == 0) {
            throw new IllegalArgumentException(
                "configured income weights for participating jurisdictions sum to zero");
        }

        List<IncomeAllocation> allocations = new ArrayList<>(participating.size());
        for (String code : participating) {
            BigDecimal share = totalIncome
                .multiply(incomeByJurisdiction.get(code))
                .divide(weightSum, 2, RoundingMode.HALF_UP);
            allocations.add(new IncomeAllocation(
                UUID.randomUUID().toString(),
                workerId,
                code,
                share,
                allocatedFor
            ));
        }
        return List.copyOf(allocations);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IncomeProportionalAllocationStrategy other)) return false;
        return incomeByJurisdiction.equals(other.incomeByJurisdiction);
    }

    @Override
    public int hashCode() {
        return Objects.hash(incomeByJurisdiction);
    }

    @Override
    public String toString() {
        return "IncomeProportionalAllocationStrategy[incomeByJurisdiction="
            + incomeByJurisdiction + "]";
    }
}
