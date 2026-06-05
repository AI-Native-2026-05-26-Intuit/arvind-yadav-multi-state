package com.uptimecrew.multistate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.uptimecrew.multistate.exception.JurisdictionUnsupportedException;
import com.uptimecrew.multistate.model.IncomeAllocation;
import com.uptimecrew.multistate.model.WorkDay;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeightedDayCountAllocationStrategyTest {

    @Test
    @DisplayName("allocate_validInput_returnsExpectedResult")
    void allocate_validInput_returnsExpectedResult() {
        // Arrange
        WeightedDayCountAllocationStrategy subject = new WeightedDayCountAllocationStrategy();
        String workerId = "emp_001";
        BigDecimal totalIncome = new BigDecimal("1000.00");
        LocalDate allocatedFor = LocalDate.of(2026, 6, 5);
        List<WorkDay> workDays = List.of(
            businessDay(workerId, "CA", LocalDate.of(2026, 6, 1)),
            businessDay(workerId, "NY", LocalDate.of(2026, 6, 2))
        );

        // Act
        List<IncomeAllocation> result = subject.allocate(workerId, totalIncome, workDays, allocatedFor);

        // Assert
        assertThat(result)
            .hasSize(2)
            .extracting(IncomeAllocation::jurisdictionCode)
            .containsExactly("CA", "NY");
    }

    @Test
    @DisplayName("allocate_negativeTotalIncome_throwsJurisdictionUnsupportedException")
    void allocate_negativeTotalIncome_throwsJurisdictionUnsupportedException() {
        // Arrange
        WeightedDayCountAllocationStrategy subject = new WeightedDayCountAllocationStrategy();
        String workerId = "emp_002";
        BigDecimal negativeIncome = new BigDecimal("-1.00");
        List<WorkDay> workDays = List.of(
            businessDay(workerId, "CA", LocalDate.of(2026, 6, 1))
        );
        LocalDate allocatedFor = LocalDate.of(2026, 6, 5);

        // Act + Assert
        assertThatThrownBy(() -> subject.allocate(workerId, negativeIncome, workDays, allocatedFor))
            .isInstanceOf(JurisdictionUnsupportedException.class)
            .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("allocate_emptyWorkDays_returnsEmptyList")
    void allocate_emptyWorkDays_returnsEmptyList() {
        // Arrange
        WeightedDayCountAllocationStrategy subject = new WeightedDayCountAllocationStrategy();
        String workerId = "emp_003";
        BigDecimal totalIncome = new BigDecimal("500.00");
        List<WorkDay> workDays = List.of();
        LocalDate allocatedFor = LocalDate.of(2026, 6, 5);

        // Act
        List<IncomeAllocation> result = subject.allocate(workerId, totalIncome, workDays, allocatedFor);

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("allocate_mixedBusinessAndWeekendDays_weightsOnlyBusinessDaysAndSumsToTotal")
    void allocate_mixedBusinessAndWeekendDays_weightsOnlyBusinessDaysAndSumsToTotal() {
        // Arrange
        WeightedDayCountAllocationStrategy subject = new WeightedDayCountAllocationStrategy();
        String workerId = "emp_004";
        BigDecimal totalIncome = new BigDecimal("1000.00");
        LocalDate allocatedFor = LocalDate.of(2026, 6, 5);
        // Mon CA, Tue CA, Sat CA (weekend ignored), Wed NY.
        List<WorkDay> workDays = List.of(
            businessDay(workerId, "CA", LocalDate.of(2026, 6, 1)),
            businessDay(workerId, "CA", LocalDate.of(2026, 6, 2)),
            businessDay(workerId, "CA", LocalDate.of(2026, 6, 6)),
            businessDay(workerId, "NY", LocalDate.of(2026, 6, 3))
        );

        // Act
        List<IncomeAllocation> result = subject.allocate(workerId, totalIncome, workDays, allocatedFor);

        // Assert
        assertThat(result)
            .hasSize(2)
            .extracting(IncomeAllocation::jurisdictionCode, IncomeAllocation::amount)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("CA", new BigDecimal("666.67")),
                org.assertj.core.groups.Tuple.tuple("NY", new BigDecimal("333.33"))
            );
    }

    private static WorkDay businessDay(String workerId, String jurisdictionCode, LocalDate date) {
        return new WorkDay(UUID.randomUUID().toString(), workerId, jurisdictionCode, date);
    }
}
