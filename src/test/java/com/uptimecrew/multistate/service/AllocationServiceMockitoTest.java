package com.uptimecrew.multistate.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.uptimecrew.multistate.entity.Tenant;
import com.uptimecrew.multistate.model.IncomeAllocation;
import com.uptimecrew.multistate.model.IncomeAllocationTestDataBuilder;
import com.uptimecrew.multistate.model.WorkDay;
import com.uptimecrew.multistate.outbox.EventOutboxEntity;
import com.uptimecrew.multistate.outbox.EventOutboxRepository;
import com.uptimecrew.multistate.readmodel.TenantReadModelRepository;
import com.uptimecrew.multistate.repository.TenantRepository;

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
    @Mock TenantRepository repository;
    @Mock TenantReadModelRepository readModelRepository;
    @Mock EventOutboxRepository outboxRepository;

    @Test
    void delegates_to_strategy_then_persists_tenant() {
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
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AllocationService subject = new AllocationService(
            strategy, repository, readModelRepository, outboxRepository);
        Tenant saved = subject.allocate(workerId, "Acme LLC", totalIncome, workDays, allocatedFor);

        verify(strategy).allocate(workerId, totalIncome, workDays, allocatedFor);
        verify(repository).save(saved);
        verify(readModelRepository).save(any());   // write-through to the Mongo read model
        verify(outboxRepository).save(any(EventOutboxEntity.class));   // outbox row enqueued in the same tx
        assertEquals("emp_42", saved.getId());
        assertEquals("Acme LLC", saved.getLegalName());
        assertEquals("CA", saved.getPrimaryJurisdictionCode());   // derived from the stubbed allocation
    }
}
