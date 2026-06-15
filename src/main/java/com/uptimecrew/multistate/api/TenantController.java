package com.uptimecrew.multistate.api;

import com.uptimecrew.multistate.clients.IdentityProfile;
import com.uptimecrew.multistate.clients.IdentityService;
import com.uptimecrew.multistate.readmodel.TenantReadModel;
import com.uptimecrew.multistate.service.AllocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants")
@Tag(name = "Tenants", description = "Tenants read API and LLM-summary endpoint")
public class TenantController {

    private static final Logger LOG = LoggerFactory.getLogger(TenantController.class);

    private final AllocationService service;
    private final IdempotencyService idempotency;
    private final IdentityService identityService;

    public TenantController(AllocationService service,
                            IdempotencyService idempotency,
                            IdentityService identityService) {
        this.service = service;
        this.idempotency = idempotency;
        this.identityService = identityService;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_tenants.read') and hasRole('TENANT_READER')")
    @Operation(summary = "Fetch a tenant by id",
               description = "Returns the denormalised read-model document for the given id.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Found"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid JWT"),
        @ApiResponse(responseCode = "403", description = "JWT present but lacks required scope or role"),
        @ApiResponse(responseCode = "404", description = "No tenant with that id")
    })
    public ResponseEntity<TenantReadModel> getById(@PathVariable String id,
                                                   @AuthenticationPrincipal Jwt jwt) {
        LOG.info("get id={} subject={}", id, jwt.getSubject());
        Optional<TenantReadModel> found = service.findById(id);
        return found.map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/summary")
    @PreAuthorize("hasAuthority('SCOPE_tenants.read') and hasRole('TENANT_READER')")
    @Operation(summary = "Generate an LLM summary for a tenant",
               description = "Idempotent POST. Requires an Idempotency-Key (UUID). "
                           + "Returns the cached body on retry with the same key.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Summary generated (or replayed from idempotency cache)"),
        @ApiResponse(responseCode = "400", description = "Idempotency-Key header missing or not a valid UUID"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid JWT"),
        @ApiResponse(responseCode = "403", description = "JWT present but lacks required scope or role"),
        @ApiResponse(responseCode = "409", description = "Concurrent request with the same Idempotency-Key still in flight"),
        @ApiResponse(responseCode = "429", description = "Per-subject rate limit exceeded")
    })
    public ResponseEntity<Map<String, String>> summary(@PathVariable String id,
                                                       @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                       @AuthenticationPrincipal Jwt jwt) {
        if (!isValidUuid(idempotencyKey)) {
            return ResponseEntity.badRequest().build();
        }
        LOG.info("summary id={} subject={} idemKey={}", id, jwt.getSubject(), idempotencyKey);
        return idempotency.handle(idempotencyKey, "tenants.summary", () -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            IdentityProfile profile = identityService.getProfile(jwt.getSubject());
            return ResponseEntity.ok(Map.of(
                "summary", "Stub LLM summary for " + id,
                "displayName", profile.displayName()));
        });
    }

    private static boolean isValidUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}