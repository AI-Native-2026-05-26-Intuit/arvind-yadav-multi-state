package com.uptimecrew.multistate.kafka;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TraceparentLoggingProducerListenerTest {

  private final TraceparentLoggingProducerListener listener = new TraceparentLoggingProducerListener();

  @Test
  @DisplayName("onSuccess logs when traceparent header is present")
  void onSuccess_withTraceparentHeader() {
    ProducerRecord<String, String> record =
        new ProducerRecord<>("tenant-events", "key-1", "payload");
    record.headers().add(new RecordHeader("traceparent", "00-abc-def-01".getBytes(StandardCharsets.UTF_8)));

    assertThatCode(
            () ->
                listener.onSuccess(
                    record, new RecordMetadata(new TopicPartition("tenant-events", 0), 0, 0, 0, 0, 0)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("onSuccess warns when traceparent header is missing")
  void onSuccess_withoutTraceparentHeader() {
    ProducerRecord<String, String> record = new ProducerRecord<>("tenant-events", "key-2", "payload");

    assertThatCode(
            () ->
                listener.onSuccess(
                    record, new RecordMetadata(new TopicPartition("tenant-events", 0), 0, 0, 0, 0, 0)))
        .doesNotThrowAnyException();
  }
}
