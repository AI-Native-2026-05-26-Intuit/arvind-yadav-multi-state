package com.uptimecrew.multistate.outbox;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.kafkaclients.v2_6.KafkaTelemetry;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.ProducerListener;

/**
 * Explicit Kafka producer wiring for the outbox publisher.
 *
 * <p>The Spring Boot Kafka autoconfig in this Boot/Kafka combo doesn't expose
 * a {@code KafkaTemplate} bean by default in this project, so we register one
 * directly. Outbox payloads are pre-serialized JSON strings, so both key and
 * value use {@link StringSerializer}. {@code acks=all} + {@code enable.idempotence}
 * gives exactly-once delivery semantics on the broker side, which the outbox
 * pattern depends on for at-least-once-without-duplicates publishing.
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    // Build the factory by hand instead of leaning on KafkaAutoConfiguration's
    // DefaultKafkaProducerFactoryCustomizer (which OTel uses to attach
    // KafkaTelemetry). To get producer-side trace-context propagation, merge
    // KafkaTelemetry's producer-interceptor props into the producer config
    // — same wiring KafkaAutoConfiguration would apply, but on our own factory.
    @Bean
    public ProducerFactory<String, String> outboxProducerFactory(OpenTelemetry openTelemetry) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.putAll(KafkaTelemetry.create(openTelemetry).producerInterceptorConfigProperties());
        return new DefaultKafkaProducerFactory<>(props);
    }

    // This KafkaTemplate is built directly, bypassing KafkaAutoConfiguration's
    // template factory — which is what normally calls setProducerListener(...)
    // for ProducerListener beans in the context. Inject the listener here so
    // the traceparent smoke check actually fires.
    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> outboxProducerFactory,
                                                       ProducerListener producerListener) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(outboxProducerFactory);
        template.setProducerListener(producerListener);
        return template;
    }
}
