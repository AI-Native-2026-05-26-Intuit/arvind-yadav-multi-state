package com.uptimecrew.multistate.service;

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
 * Blends two delegate allocation strategies by a fixed weight. Each jurisdiction's blended
 * amount is {@code primaryWeight * primaryShare + (1 - primaryWeight) * secondaryShare},
 * rounded HALF_UP to scale 2.
 */
public final class HybridAllocationStrategy implements AllocationStrategy {

    private final AllocationStrategy primary;
    private final AllocationStrategy secondary;
    private final BigDecimal primaryWeight;

    public HybridAllocationStrategy(
        AllocationStrategy primary,
        AllocationStrategy secondary,
        BigDecimal primaryWeight
    ) {
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(secondary, "secondary");
        Objects.requireNonNull(primaryWeight, "primaryWeight");
        if (primaryWeight.signum() < 0) {
            throw new IllegalArgumentException(
                "primaryWeight must be in [0, 1]: " + primaryWeight);
        }
        if (primaryWeight.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                "primaryWeight must be in [0, 1]: " + primaryWeight);
        }
        this.primary = primary;
        this.secondary = secondary;
        this.primaryWeight = primaryWeight.setScale(2, RoundingMode.HALF_UP);
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

        BigDecimal secondaryWeight = BigDecimal.ONE.subtract(primaryWeight);
        List<IncomeAllocation> primaryShares =
            primary.allocate(workerId, totalIncome, workDays, allocatedFor);
        List<IncomeAllocation> secondaryShares =
            secondary.allocate(workerId, totalIncome, workDays, allocatedFor);

        Map<String, BigDecimal> blended = new LinkedHashMap<>();
        for (IncomeAllocation a : primaryShares) {
            blended.merge(
                a.jurisdictionCode(),
                a.amount().multiply(primaryWeight),
                BigDecimal::add);
        }
        for (IncomeAllocation a : secondaryShares) {
            blended.merge(
                a.jurisdictionCode(),
                a.amount().multiply(secondaryWeight),
                BigDecimal::add);
        }

        List<IncomeAllocation> allocations = new ArrayList<>(blended.size());
        for (Map.Entry<String, BigDecimal> entry : blended.entrySet()) {
            allocations.add(new IncomeAllocation(
                UUID.randomUUID().toString(),
                workerId,
                entry.getKey(),
                entry.getValue().setScale(2, RoundingMode.HALF_UP),
                allocatedFor
            ));
        }
        return List.copyOf(allocations);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HybridAllocationStrategy other)) return false;
        return primary.equals(other.primary)
            && secondary.equals(other.secondary)
            && primaryWeight.equals(other.primaryWeight);
    }

    @Override
    public int hashCode() {
        return Objects.hash(primary, secondary, primaryWeight);
    }

    @Override
    public String toString() {
        return "HybridAllocationStrategy[primary=" + primary
            + ", secondary=" + secondary
            + ", primaryWeight=" + primaryWeight + "]";
    }
}
