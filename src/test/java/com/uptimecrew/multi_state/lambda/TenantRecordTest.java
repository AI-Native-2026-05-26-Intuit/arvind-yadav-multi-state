package com.uptimecrew.multi_state.lambda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

class TenantRecordTest {

  @Test
  @DisplayName("fromItem maps a complete DynamoDB item")
  void fromItem_mapsCompleteItem() {
    Map<String, AttributeValue> item =
        Map.of(
            "id", AttributeValue.builder().s("tnt_1").build(),
            "legalName", AttributeValue.builder().s("Acme LLC").build(),
            "primaryState", AttributeValue.builder().s("CA").build(),
            "status", AttributeValue.builder().s("ACTIVE").build(),
            "capturedAt", AttributeValue.builder().s("2026-01-15T12:00:00Z").build(),
            "totalAllocated", AttributeValue.builder().n("1234.5").build());

    TenantRecord record = TenantRecord.fromItem(item);

    assertThat(record.id()).isEqualTo("tnt_1");
    assertThat(record.legalName()).isEqualTo("Acme LLC");
    assertThat(record.primaryState()).isEqualTo("CA");
    assertThat(record.status()).isEqualTo("ACTIVE");
    assertThat(record.capturedAt()).isEqualTo(Instant.parse("2026-01-15T12:00:00Z"));
    assertThat(record.totalAllocated()).isEqualByComparingTo(new BigDecimal("1234.50"));
  }

  @Test
  @DisplayName("fromItem accepts money stored as string attribute")
  void fromItem_acceptsMoneyAsString() {
    Map<String, AttributeValue> item =
        Map.of(
            "id", AttributeValue.builder().s("tnt_2").build(),
            "legalName", AttributeValue.builder().s("Beta").build(),
            "primaryState", AttributeValue.builder().s("NY").build(),
            "status", AttributeValue.builder().s("ACTIVE").build(),
            "capturedAt", AttributeValue.builder().s("2026-01-01T00:00:00Z").build(),
            "totalAllocated", AttributeValue.builder().s("99.99").build());

    assertThat(TenantRecord.fromItem(item).totalAllocated())
        .isEqualByComparingTo(new BigDecimal("99.99"));
  }

  @Test
  @DisplayName("fromItem rejects missing string attribute")
  void fromItem_rejectsMissingString() {
    Map<String, AttributeValue> item =
        Map.of(
            "id", AttributeValue.builder().s("tnt_3").build(),
            "legalName", AttributeValue.builder().s("Gamma").build(),
            "primaryState", AttributeValue.builder().s("TX").build(),
            "status", AttributeValue.builder().s("ACTIVE").build(),
            "capturedAt", AttributeValue.builder().s("2026-01-01T00:00:00Z").build());

    assertThatThrownBy(() -> TenantRecord.fromItem(item))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("totalAllocated");
  }

  @Test
  @DisplayName("fromItem rejects blank string attribute")
  void fromItem_rejectsBlankString() {
    Map<String, AttributeValue> item =
        Map.of(
            "id", AttributeValue.builder().s("tnt_4").build(),
            "legalName", AttributeValue.builder().s(" ").build(),
            "primaryState", AttributeValue.builder().s("CA").build(),
            "status", AttributeValue.builder().s("ACTIVE").build(),
            "capturedAt", AttributeValue.builder().s("2026-01-01T00:00:00Z").build(),
            "totalAllocated", AttributeValue.builder().n("1.00").build());

    assertThatThrownBy(() -> TenantRecord.fromItem(item))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("legalName");
  }

  @Test
  @DisplayName("fromItem rejects null item map")
  void fromItem_rejectsNullItem() {
    assertThatThrownBy(() -> TenantRecord.fromItem(null))
        .isInstanceOf(NullPointerException.class);
  }
}
