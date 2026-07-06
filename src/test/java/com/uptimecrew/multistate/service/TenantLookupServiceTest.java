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
}
