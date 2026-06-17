package com.uptimecrew.multistate.graphql;

/**
 * Structured-output target for the {@code summarizeTenant} GraphQL mutation —
 * a record bound by Spring AI's {@code .entity(TenantSummary.class)} converter
 * and then re-validated against {@code resources/schemas/TenantSummary.schema.json}
 * so a future model release that drifts the shape fails loudly instead of
 * silently shipping a malformed summary.
 */
public record TenantSummary(
        String primaryState,
        Double totalAllocation,
        Integer stateCount,
        String complianceTier) { }