package com.uptimecrew.multi_state.lambda;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmfPublisherTest {

  @Test
  void emfLineUsesMultistateDevNamespaceAndMetricName() {
    String line = EmfPublisher.toEmfLine("TenantLookupSuccess", 1);

    assertThat(line).contains("\"Namespace\":\"MultistateDev\"");
    assertThat(line).contains("\"TenantLookupSuccess\":1");
    assertThat(line).contains("\"Unit\":\"Count\"");
    assertThat(line).contains("\"Service\":\"tenant-lookup\"");
  }
}
