package com.uptimecrew.multistate.kafka;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.stereotype.Component;

/**
 * Logs the W3C {@code traceparent} header on every successful Kafka send so an
 * engineer can confirm trace-context propagation from the bootRun log without
 * opening Jaeger.
 *
 * <p>The {@code opentelemetry-spring-kafka-2.7} instrumentation installs a
 * {@code ProducerInterceptor} under the hood that injects {@code traceparent}
 * (and {@code tracestate}) into outgoing record headers before this listener
 * fires. If the header is missing here, the instrumentation isn't on the
 * classpath OR {@code otel.instrumentation.spring-kafka.enabled} is false.
 *
 * <p>Generic types match the outbox {@code KafkaTemplate<String, String>}
 * registered in {@link com.uptimecrew.multistate.outbox.KafkaProducerConfig}.
 * Spring Kafka does <strong>not</strong> auto-attach {@code ProducerListener}
 * beans to user-built {@code KafkaTemplate}s — see
 * {@code KafkaProducerConfig.kafkaTemplate(...)} where {@code setProducerListener}
 * is called explicitly.
 */
@Component
public final class TraceparentLoggingProducerListener implements ProducerListener<String, String> {

    private static final Logger LOG = LoggerFactory.getLogger(TraceparentLoggingProducerListener.class);
    private static final String TRACEPARENT = "traceparent";

    @Override
    public void onSuccess(ProducerRecord<String, String> record, RecordMetadata recordMetadata) {
        Header header = record.headers().lastHeader(TRACEPARENT);
        if (header == null) {
            LOG.warn("outgoing kafka record has NO traceparent header topic={} key={}",
                     record.topic(), record.key());
            return;
        }
        LOG.info("outgoing traceparent={} topic={} key={}",
                 new String(header.value()), record.topic(), record.key());
    }
}
