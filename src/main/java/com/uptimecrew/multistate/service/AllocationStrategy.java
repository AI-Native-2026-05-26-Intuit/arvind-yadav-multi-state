package com.uptimecrew.multistate.service;

import com.uptimecrew.multistate.model.IncomeAllocation;
import com.uptimecrew.multistate.model.WorkDay;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface AllocationStrategy {

    List<IncomeAllocation> allocate(
        String workerId,
        BigDecimal totalIncome,
        List<WorkDay> workDays,
        LocalDate allocatedFor
    );
}
