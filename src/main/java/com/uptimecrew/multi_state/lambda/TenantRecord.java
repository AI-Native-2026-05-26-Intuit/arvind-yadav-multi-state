package com.uptimecrew.multi_state.lambda;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * DynamoDB read-model projection for a tenant — serialised as JSON by
 * {@link TenantLookupHandler} on a successful lookup.
 */
public record TenantRecord(
        String id,
        String legalName,
        String primaryState,
        String status,
        Instant capturedAt,
        BigDecimal totalAllocated) {

  public TenantRecord {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(legalName, "legalName");
    Objects.requireNonNull(primaryState, "primaryState");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(capturedAt, "capturedAt");
    Objects.requireNonNull(totalAllocated, "totalAllocated");
    totalAllocated = totalAllocated.setScale(2, RoundingMode.HALF_UP);
  }

  static TenantRecord fromItem(Map<String, AttributeValue> item) {
    Objects.requireNonNull(item, "item");
    return new TenantRecord(
        requiredString(item, "id"),
        requiredString(item, "legalName"),
        requiredString(item, "primaryState"),
        requiredString(item, "status"),
        Instant.parse(requiredString(item, "capturedAt")),
        new BigDecimal(requiredString(item, "totalAllocated")).setScale(2, RoundingMode.HALF_UP));
  }

  private static String requiredString(Map<String, AttributeValue> item, String key) {
    AttributeValue value = item.get(key);
    if (value == null || value.s() == null || value.s().isBlank()) {
      throw new IllegalArgumentException("missing or blank DynamoDB attribute: " + key);
    }
    return value.s();
  }
}
