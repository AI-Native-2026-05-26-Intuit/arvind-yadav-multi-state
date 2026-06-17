package com.uptimecrew.multistate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.uptimecrew.multistate.exception.IncomeAllocationFailedException;
import com.uptimecrew.multistate.exception.JurisdictionUnsupportedException;
import com.uptimecrew.multistate.model.WorkDay;
import com.uptimecrew.multistate.outbox.EventOutboxRepository;
import com.uptimecrew.multistate.readmodel.TenantReadModelRepository;
import com.uptimecrew.multistate.repository.TenantRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
class AllocationServiceExceptionPathTest {

    private static final String WORKER_ID = "emp_42";
    private static final String LEGAL_NAME = "Acme LLC";
    private static final BigDecimal TOTAL_INCOME = new BigDecimal("12500.00");
    private static final LocalDate PERIOD = LocalDate.of(2026, 3, 31);
    private static final List<WorkDay> WORK_DAYS = List.of(
        new WorkDay("wd-1", "emp_42", "ZZ", LocalDate.of(2026, 3, 1))
    );

    @Mock AllocationStrategy strategy;
    @Mock TenantRepository repository;
    @Mock TenantReadModelRepository readModelRepository;
    @Mock EventOutboxRepository outboxRepository;

    private Logger logbackLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logbackLogger = (Logger) LoggerFactory.getLogger(AllocationService.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logbackLogger.detachAppender(appender);
    }

    @Test
    void throws_typed_domain_exception_when_strategy_rejects_jurisdiction() {
        when(strategy.allocate(any(), any(), any(), any()))
            .thenThrow(new JurisdictionUnsupportedException("jurisdiction not supported: ZZ"));

        AllocationService subject = new AllocationService(strategy, repository, readModelRepository, outboxRepository);

        assertThatThrownBy(() ->
            subject.allocate(WORKER_ID, LEGAL_NAME, TOTAL_INCOME, WORK_DAYS, PERIOD))
            .isInstanceOf(JurisdictionUnsupportedException.class)
            .hasMessageContaining("jurisdiction not supported: ZZ");
    }

    @Test
    void preserves_io_cause_when_strategy_wraps_underlying_failure() {
        IOException underlying = new IOException("synthetic cause: revenue lookup missed for ZZ");
        when(strategy.allocate(any(), any(), any(), any()))
            .thenThrow(new IncomeAllocationFailedException(
                "failed reading day-count source for tenant-a", underlying));

        AllocationService subject = new AllocationService(strategy, repository, readModelRepository, outboxRepository);

        assertThatThrownBy(() ->
            subject.allocate(WORKER_ID, LEGAL_NAME, TOTAL_INCOME, WORK_DAYS, PERIOD))
            .isInstanceOf(IncomeAllocationFailedException.class)
            .hasMessageContaining("failed reading day-count source for tenant-a")
            .hasRootCauseInstanceOf(IOException.class)
            .hasRootCauseMessage("synthetic cause: revenue lookup missed for ZZ");
    }

    @Test
    void emits_single_warn_log_with_exception_message_when_strategy_fails() {
        when(strategy.allocate(any(), any(), any(), any()))
            .thenThrow(new JurisdictionUnsupportedException("jurisdiction not supported: ZZ"));

        AllocationService subject = new AllocationService(strategy, repository, readModelRepository, outboxRepository);

        assertThatThrownBy(() ->
            subject.allocate(WORKER_ID, LEGAL_NAME, TOTAL_INCOME, WORK_DAYS, PERIOD))
            .isInstanceOf(JurisdictionUnsupportedException.class);

        assertThat(appender.list)
            .filteredOn(ev -> ev.getLevel() == Level.WARN)
            .hasSize(1)
            .extracting(ILoggingEvent::getFormattedMessage)
            .anyMatch(s -> s.contains("jurisdiction not supported: ZZ"));
    }
}
