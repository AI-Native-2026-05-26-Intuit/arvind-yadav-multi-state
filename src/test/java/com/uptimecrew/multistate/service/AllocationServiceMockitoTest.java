package com.uptimecrew.multistate.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.uptimecrew.multistate.model.IncomeAllocation;
import com.uptimecrew.multistate.model.IncomeAllocationTestDataBuilder;
import com.uptimecrew.multistate.model.WorkDay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AllocationServiceMockitoTest {

    @Mock AllocationStrategy strategy;

    @Test
    void delegates_to_injected_strategy_on_allocate() {
        String workerId = "emp_42";
        BigDecimal totalIncome = new BigDecimal("12500.00");
        List<WorkDay> workDays = List.of(
            new WorkDay("wd-1", "emp_42", "CA", LocalDate.of(2026, 3, 1))
        );
        LocalDate allocatedFor = LocalDate.of(2026, 3, 31);

        List<IncomeAllocation> stubbedReturn = List.of(
            IncomeAllocationTestDataBuilder.aIncomeAllocation()
                .withId("alloc-001")
                .withWorkerId("emp_42")
                .withJurisdictionCode("CA")
                .withAmount(new BigDecimal("12500.00"))
                .withAllocatedFor(allocatedFor)
                .build());

        when(strategy.allocate(any(), any(), any(), any())).thenReturn(stubbedReturn);

        AllocationService subject = new AllocationService(strategy);
        List<IncomeAllocation> result =
            subject.allocate(workerId, totalIncome, workDays, allocatedFor);

        verify(strategy).allocate(workerId, totalIncome, workDays, allocatedFor);
        assertEquals(stubbedReturn, result);
    }
}
