package com.uptimecrew.multistate.service;

import com.uptimecrew.multistate.exception.AllocationException;
import com.uptimecrew.multistate.model.IncomeAllocation;
import com.uptimecrew.multistate.model.WorkDay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
 *
 * <p>Spring owns this bean's lifecycle. The single-constructor injection point needs no
 * {@code @Autowired} (Spring 6); Spring supplies the {@code @Primary} {@link AllocationStrategy}.
 */
@Service
public final class AllocationService {

    private static final Logger LOG = LoggerFactory.getLogger(AllocationService.class);

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

        LOG.info("invoking strategy={} for workerId={} totalIncome={} workDays={} allocatedFor={}",
            strategy.getClass().getSimpleName(), workerId, totalIncome, workDays.size(), allocatedFor);
        try {
            List<IncomeAllocation> result =
                strategy.allocate(workerId, totalIncome, workDays, allocatedFor);
            List<IncomeAllocation> immutable = List.copyOf(result);
            LOG.info("strategy={} returned allocations={} for workerId={}",
                strategy.getClass().getSimpleName(), immutable.size(), workerId);
            return immutable;
        } catch (AllocationException ex) {
            LOG.warn("strategy failed: {}", ex.getMessage(), ex);
            throw ex;
        }
    }
}
