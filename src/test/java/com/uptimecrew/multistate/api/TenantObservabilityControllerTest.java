package com.uptimecrew.multistate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.uptimecrew.multistate.readmodel.TenantReadModel;
import com.uptimecrew.multistate.service.TenantLookupService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class TenantObservabilityControllerTest {

  @Mock TenantLookupService tenantLookup;
  @InjectMocks TenantObservabilityController controller;

  @Test
  @DisplayName("returns 200 when tenant exists")
  void returns200WhenFound() {
    TenantReadModel tenant =
        new TenantReadModel("t-1", "CA", "Acme", "ACTIVE", Instant.parse("2026-01-01T00:00:00Z"), List.of());
    when(tenantLookup.lookupById("t-1")).thenReturn(Optional.of(tenant));

    var response = controller.getByTenantId("t-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo(tenant);
  }

  @Test
  @DisplayName("returns 404 when tenant is missing")
  void returns404WhenMissing() {
    when(tenantLookup.lookupById("missing")).thenReturn(Optional.empty());

    var response = controller.getByTenantId("missing");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isNull();
  }
}
