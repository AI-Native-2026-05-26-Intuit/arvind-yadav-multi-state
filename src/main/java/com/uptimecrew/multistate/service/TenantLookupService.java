package com.uptimecrew.multistate.service;

import com.uptimecrew.multistate.readmodel.TenantReadModel;
import com.uptimecrew.multistate.readmodel.TenantReadModelRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Tenant read + nexus-by-state lookups with pre-registered Micrometer instruments.
 * Counters and timers are built once in the constructor — never on the hot path.
 */
@Service
public final class TenantLookupService {

  private static final Logger LOG = LoggerFactory.getLogger(TenantLookupService.class);

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
    LOG.info("lookup attempted: tenantId={}", id);
    return byIdTimer.record(
        () -> {
          try {
            Optional<TenantReadModel> found = loadTenantFromStores(id);
            if (found.isPresent()) {
              byIdSuccess.increment();
            } else {
              byIdNotFound.increment();
              LOG.info("lookup result: not_found tenantId={}", id);
            }
            return found;
          } catch (RuntimeException ex) {
            byIdError.increment();
            throw ex;
          }
        });
  }

  /** Postgres / Mongo read path — child span visible in Tempo when the OTel agent is attached. */
  @WithSpan
  private Optional<TenantReadModel> loadTenantFromStores(String tenantId) {
    return allocationService.findById(tenantId);
  }

  public List<TenantReadModel> nexusForState(String state) {
    LOG.info("lookup attempted: state={}", state);
    return byStateTimer.record(
        () -> {
          try {
            List<TenantReadModel> rows = readModelRepository.findByPrimaryState(state);
            if (rows.isEmpty()) {
              byStateNotFound.increment();
              LOG.info("lookup result: not_found state={}", state);
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
