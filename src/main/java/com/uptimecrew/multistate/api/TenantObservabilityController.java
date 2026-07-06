package com.uptimecrew.multistate.api;

import com.uptimecrew.multistate.readmodel.TenantReadModel;
import com.uptimecrew.multistate.service.TenantLookupService;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated tenant lookup for observability smoke / SLO traffic.
 * Maps to {@code GET /tenants/{tenantId}} so Micrometer {@code uri} tags align
 * with the Sloth spec ({@code /tenants/{tenantId}}).
 */
@RestController
public final class TenantObservabilityController {

  private final TenantLookupService tenantLookup;

  public TenantObservabilityController(TenantLookupService tenantLookup) {
    this.tenantLookup = Objects.requireNonNull(tenantLookup, "tenantLookup");
  }

  @GetMapping("/tenants/{tenantId}")
  public ResponseEntity<TenantReadModel> getByTenantId(@PathVariable String tenantId) {
    Optional<TenantReadModel> found = tenantLookup.lookupById(tenantId);
    return found.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
  }
}
