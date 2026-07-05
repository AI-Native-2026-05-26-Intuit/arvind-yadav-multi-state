package com.uptimecrew.multistate.service;

import com.uptimecrew.multistate.readmodel.TenantReadModel;
import com.uptimecrew.multistate.readmodel.TenantReadModelRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Tenant read + nexus-by-state lookups with pre-registered Micrometer instruments.
 * Counters and timers are built once in the constructor — never on the hot path.
 */
@Service
public final class TenantLookupService {

  static final String EVALUATIONS_COUNTER = "multistate_nexus_evaluations_total";
  static final String EVALUATIONS_TIMER = "multistate_nexus_evaluations";

  private final AllocationService allocationService;
  private final TenantReadModelRepository readModelRepository;

  private final Counter byIdSuccess;
  private final Counter byIdNotFound;
  private final Counter byIdError;
  private final Counter byStateSuccess;
  private final Counter byStateNotFound;
  private final Counter byStateError;
  private final Timer byIdTimer;
  private final Timer byStateTimer;

  public TenantLookupService(MeterRegistry registry,
                             AllocationService allocationService,
                             TenantReadModelRepository readModelRepository) {
    this.allocationService = Objects.requireNonNull(allocationService, "allocationService");
    this.readModelRepository = Objects.requireNonNull(readModelRepository, "readModelRepository");
    Objects.requireNonNull(registry, "registry");

    this.byIdSuccess = evaluationCounter(registry, "by_id", "success");
    this.byIdNotFound = evaluationCounter(registry, "by_id", "not_found");
    this.byIdError = evaluationCounter(registry, "by_id", "error");
    this.byStateSuccess = evaluationCounter(registry, "by_state", "success");
    this.byStateNotFound = evaluationCounter(registry, "by_state", "not_found");
    this.byStateError = evaluationCounter(registry, "by_state", "error");

    this.byIdTimer =
        Timer.builder(EVALUATIONS_TIMER)
            .description("Wall time for tenant nexus evaluations")
            .tag("tenant_type", "by_id")
            .register(registry);
    this.byStateTimer =
        Timer.builder(EVALUATIONS_TIMER)
            .description("Wall time for tenant nexus evaluations")
            .tag("tenant_type", "by_state")
            .register(registry);
  }

  private static Counter evaluationCounter(
      MeterRegistry registry, String tenantType, String outcome) {
    return Counter.builder(EVALUATIONS_COUNTER)
        .description("Nexus evaluation outcomes")
        .tag("tenant_type", tenantType)
        .tag("outcome", outcome)
        .register(registry);
  }

  public Optional<TenantReadModel> lookupById(String id) {
    return byIdTimer.record(
        () -> {
          try {
            Optional<TenantReadModel> found = allocationService.findById(id);
            if (found.isPresent()) {
              byIdSuccess.increment();
            } else {
              byIdNotFound.increment();
            }
            return found;
          } catch (RuntimeException ex) {
            byIdError.increment();
            throw ex;
          }
        });
  }

  public List<TenantReadModel> nexusForState(String state) {
    return byStateTimer.record(
        () -> {
          try {
            List<TenantReadModel> rows = readModelRepository.findByPrimaryState(state);
            if (rows.isEmpty()) {
              byStateNotFound.increment();
            } else {
              byStateSuccess.increment();
            }
            return rows;
          } catch (RuntimeException ex) {
            byStateError.increment();
            throw ex;
          }
        });
  }
}
