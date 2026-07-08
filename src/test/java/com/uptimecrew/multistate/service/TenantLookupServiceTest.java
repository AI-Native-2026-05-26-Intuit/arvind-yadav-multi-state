package com.uptimecrew.multistate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.uptimecrew.multistate.readmodel.TenantReadModel;
import com.uptimecrew.multistate.readmodel.TenantReadModelRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TenantLookupServiceTest {

  @Mock AllocationService allocationService;
  @Mock TenantReadModelRepository readModelRepository;

  SimpleMeterRegistry registry;
  TenantLookupService service;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    service = new TenantLookupService(registry, allocationService, readModelRepository, 0L, "tnt_synth_slow");
  }

  @Test
  @DisplayName("lookupById increments success counter when tenant exists")
  void incrementsSuccessCounterWhenTenantFound() {
    TenantReadModel tenant =
        new TenantReadModel("t-1", "CA", "Acme", "ACTIVE", Instant.parse("2026-01-01T00:00:00Z"), List.of());
    when(allocationService.findById("t-1")).thenReturn(Optional.of(tenant));

    assertThat(service.lookupById("t-1")).contains(tenant);
    assertThat(registry.get("multistate_nexus_evaluations_total")
            .tag("tenant_type", "by_id")
            .tag("outcome", "success")
            .counter()
            .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("nexusForState increments not_found when corpus is empty")
  void incrementsNotFoundWhenStateHasNoTenants() {
    when(readModelRepository.findByPrimaryState("ZZ")).thenReturn(List.of());

    assertThat(service.nexusForState("ZZ")).isEmpty();
    assertThat(registry.get("multistate_nexus_evaluations_total")
            .tag("tenant_type", "by_state")
            .tag("outcome", "not_found")
            .counter()
            .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("lookupById increments not_found when tenant is missing")
  void incrementsNotFoundWhenTenantMissing() {
    when(allocationService.findById("missing")).thenReturn(Optional.empty());

    assertThat(service.lookupById("missing")).isEmpty();
    assertThat(registry.get("multistate_nexus_evaluations_total")
            .tag("tenant_type", "by_id")
            .tag("outcome", "not_found")
            .counter()
            .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("nexusForState increments success when tenants exist")
  void incrementsSuccessWhenStateHasTenants() {
    TenantReadModel tenant =
        new TenantReadModel("t-2", "CA", "Beta", "ACTIVE", Instant.parse("2026-01-01T00:00:00Z"), List.of());
    when(readModelRepository.findByPrimaryState("CA")).thenReturn(List.of(tenant));

    assertThat(service.nexusForState("CA")).containsExactly(tenant);
    assertThat(registry.get("multistate_nexus_evaluations_total")
            .tag("tenant_type", "by_state")
            .tag("outcome", "success")
            .counter()
            .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("lookupById applies slow-path delay for configured id prefix")
  void appliesSlowPathDelayForConfiguredPrefix() {
    service =
        new TenantLookupService(registry, allocationService, readModelRepository, 50L, "tnt_synth_slow");
    TenantReadModel tenant =
        new TenantReadModel(
            "tnt_synth_slow001", "CA", "Slow", "ACTIVE", Instant.parse("2026-01-01T00:00:00Z"), List.of());
    when(allocationService.findById("tnt_synth_slow001")).thenReturn(Optional.of(tenant));

    long started = System.currentTimeMillis();
    assertThat(service.lookupById("tnt_synth_slow001")).contains(tenant);
    assertThat(System.currentTimeMillis() - started).isGreaterThanOrEqualTo(50L);
  }

  @Test
  @DisplayName("lookupById increments error counter when store throws")
  void incrementsErrorCounterWhenStoreThrows() {
    when(allocationService.findById("boom")).thenThrow(new IllegalStateException("store down"));

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class, () -> service.lookupById("boom"));
    assertThat(registry.get("multistate_nexus_evaluations_total")
            .tag("tenant_type", "by_id")
            .tag("outcome", "error")
            .counter()
            .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("nexusForState increments error counter when repository throws")
  void incrementsErrorCounterWhenNexusLookupThrows() {
    when(readModelRepository.findByPrimaryState("CA"))
        .thenThrow(new IllegalStateException("mongo down"));

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class, () -> service.nexusForState("CA"));
    assertThat(registry.get("multistate_nexus_evaluations_total")
            .tag("tenant_type", "by_state")
            .tag("outcome", "error")
            .counter()
            .count())
        .isEqualTo(1.0);
  }
}
