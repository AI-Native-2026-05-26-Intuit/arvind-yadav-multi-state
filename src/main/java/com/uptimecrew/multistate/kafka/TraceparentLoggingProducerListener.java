package com.uptimecrew.multistate.kafka;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.stereotype.Component;

// A tiny ProducerListener that logs the traceparent header on every send so
// engineers can eyeball trace-context propagation in the bootRun log without
// having to open Jaeger. The opentelemetry-spring-kafka-2.7 instrumentation
// installs a ProducerInterceptor under the hood that injects the W3C
// traceparent header before this ProducerListener fires — so if the header is
// missing here, the instrumentation is not on the classpath or
// otel.instrumentation.spring-kafka.enabled is false.
@Component
@SuppressWarnings("rawtypes")
public final class TraceparentLoggingProducerListener implements ProducerListener {

    private static final Logger LOG = LoggerFactory.getLogger(TraceparentLoggingProducerListener.class);

    @Override
    public void onSuccess(ProducerRecord record, RecordMetadata recordMetadata) {
        Header header = record.headers().lastHeader("traceparent");
        if (header == null) {
            LOG.warn("outgoing kafka record has NO traceparent header topic={} key={}",
                     record.topic(), record.key());
            return;
        }
        LOG.info("outgoing traceparent={} topic={} key={}",
                 new String(header.value()), record.topic(), record.key());
    }
}
