package com.uptimecrew.multistate.readmodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.uptimecrew.multistate.consumer.AllocationCreatedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TenantReadModelTest {

  @Test
  @DisplayName("applyEvent updates scalar fields when aggregate id matches")
  void applyEvent_updatesScalarsWhenIdsMatch() {
    TenantReadModel model = new TenantReadModel("t-1");
    AllocationCreatedEvent event =
        new AllocationCreatedEvent(
            "AllocationCreated",
            "t-1",
            "CA",
            "ACTIVE",
            "day_count",
            LocalDate.of(2026, 1, 1),
            2,
            new BigDecimal("100.00"),
            Instant.parse("2026-02-01T00:00:00Z"));

    model.applyEvent(event);

    assertThat(model.getPrimaryState()).isEqualTo("CA");
    assertThat(model.getStatus()).isEqualTo("ACTIVE");
    assertThat(model.getCapturedAt()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
  }

  @Test
  @DisplayName("applyEvent rejects aggregate id mismatch")
  void applyEvent_rejectsAggregateIdMismatch() {
    TenantReadModel model = new TenantReadModel("t-1");
    AllocationCreatedEvent event =
        new AllocationCreatedEvent(
            "AllocationCreated",
            "other",
            "CA",
            "ACTIVE",
            "day_count",
            LocalDate.of(2026, 1, 1),
            1,
            new BigDecimal("10.00"),
            Instant.parse("2026-02-01T00:00:00Z"));

    assertThatThrownBy(() -> model.applyEvent(event))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match");
  }

  @Test
  @DisplayName("getTags returns empty list when backing field is null")
  void getTags_returnsEmptyWhenNull() throws Exception {
    TenantReadModel model =
        new TenantReadModel("t-2", "NY", "Beta", "ACTIVE", Instant.parse("2026-01-01T00:00:00Z"), List.of());
    var tagsField = TenantReadModel.class.getDeclaredField("tags");
    tagsField.setAccessible(true);
    tagsField.set(model, null);

    assertThat(model.getTags()).isEmpty();
  }

  @Test
  @DisplayName("setTags normalises null to empty list")
  void setTags_normalisesNullToEmpty() {
    TenantReadModel model = new TenantReadModel("t-3");
    model.setTags(null);

    assertThat(model.getTags()).isEmpty();
  }

  @Test
  @DisplayName("setTags copies provided values")
  void setTags_copiesValues() {
    TenantReadModel model = new TenantReadModel("t-4");
    model.setTags(List.of("enterprise", "west"));

    assertThat(model.getTags()).containsExactly("enterprise", "west");
  }
}
