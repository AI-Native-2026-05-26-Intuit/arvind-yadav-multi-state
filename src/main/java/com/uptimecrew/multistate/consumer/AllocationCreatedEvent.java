package com.uptimecrew.multistate.consumer;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Wire format for the {@code tenants.events} payloads produced by the outbox
 * (see {@code AllocationService.allocate(...)}). The producer side writes
 * {@code tenantId}; we read it as {@code aggregateId} here because that's the
 * domain-neutral name the consumer pipeline reasons about.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} keeps the consumer
 * forward-compatible: a producer adding a new field doesn't break replay.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AllocationCreatedEvent(
    String eventType,
    @JsonAlias({"tenantId", "aggregateId"})
    @JsonProperty("tenantId")
    String aggregateId,
    String primaryJurisdictionCode,
    String status,
    String strategy,
    LocalDate allocatedFor,
    int allocationCount,
    BigDecimal totalIncome,
    Instant occurredAt
) { }
