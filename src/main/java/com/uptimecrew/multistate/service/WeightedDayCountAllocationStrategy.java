package com.uptimecrew.multistate.service;

import com.uptimecrew.multistate.exception.JurisdictionUnsupportedException;
import com.uptimecrew.multistate.model.IncomeAllocation;
import com.uptimecrew.multistate.model.WorkDay;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Splits totalIncome across jurisdictions weighted by business-day count.
 * Weekends are ignored; each business day contributes one unit, scaled by the
 * per-jurisdiction weight (default 1.00). Shares are HALF_UP to scale 2.
 *
 * <p>Spring-managed: registered as a secondary {@link AllocationStrategy} bean (no
 * {@code @Primary}). Spring instantiates it via the no-arg constructor, giving every
 * jurisdiction the default weight; callers wanting custom weights still build it directly
 * or via {@link AllocationStrategies}.
 */
@Component
public final class WeightedDayCountAllocationStrategy implements AllocationStrategy {

    private static final BigDecimal DEFAULT_WEIGHT = new BigDecimal("1.00");

    private final Map<String, BigDecimal> weightsByJurisdiction;

    public WeightedDayCountAllocationStrategy() {
        this(Map.of());
    }

    public WeightedDayCountAllocationStrategy(Map<String, BigDecimal> weightsByJurisdiction) {
        Objects.requireNonNull(weightsByJurisdiction, "weightsByJurisdiction");
        this.weightsByJurisdiction = Map.copyOf(weightsByJurisdiction);
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
            throw new JurisdictionUnsupportedException(
                "WeightedDayCountAllocationStrategy cannot allocate negative totalIncome: "
                    + totalIncome + " for worker " + workerId);
        }
        if (workDays.isEmpty()) {
            return List.of();
        }

        Map<String, BigDecimal> weightedDays = sumWeightedBusinessDays(workDays);
        if (weightedDays.isEmpty()) {
            return List.of();
        }

        BigDecimal totalWeight = weightedDays.values().stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<IncomeAllocation> allocations = new ArrayList<>(weightedDays.size());
        for (Map.Entry<String, BigDecimal> entry : weightedDays.entrySet()) {
            BigDecimal share = totalIncome
                .multiply(entry.getValue())
                .divide(totalWeight, 2, RoundingMode.HALF_UP);
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

    private Map<String, BigDecimal> sumWeightedBusinessDays(List<WorkDay> workDays) {
        Map<String, BigDecimal> weighted = new LinkedHashMap<>();
        for (WorkDay day : workDays) {
            if (!isBusinessDay(day.workedOn())) {
                continue;
            }
            BigDecimal weight = weightsByJurisdiction.getOrDefault(day.jurisdictionCode(), DEFAULT_WEIGHT);
            weighted.merge(day.jurisdictionCode(), weight, BigDecimal::add);
        }
        return weighted;
    }

    private static boolean isBusinessDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }
}
