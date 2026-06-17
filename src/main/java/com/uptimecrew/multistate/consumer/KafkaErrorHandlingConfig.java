package com.uptimecrew.multistate.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Consumer-side resilience for {@link AllocationCreatedListener}:
 * three retries one second apart, then ship the failed record to
 * {@code tenants.events.DLT}. ErrorHandlingDeserializer (wired in
 * application.yml) is what lets a malformed JSON payload surface as a
 * handler exception in the first place — without it, the deserializer
 * would throw on the consumer thread and the container would stop.
 *
 * <p>Partition mapping mirrors the source: a record on partition {@code N}
 * is republished to partition {@code N} of the DLT. That keeps the DLT
 * partition layout stable so an operator inspecting the DLT can correlate
 * back to the original partition without further metadata.
 */
@Configuration
@EnableKafka
public class KafkaErrorHandlingConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:multistate-read-model-builder}")
    private String groupId;

    /**
     * Project-wide {@link ObjectMapper} used by the consumer (and any other
     * component that needs JSON). Registered here because Spring Boot's
     * Jackson autoconfig isn't exposing a bean in this Boot 4 setup, and
     * the consumer needs JSR-310 support for {@code Instant} / {@code LocalDate}
     * in the event payload.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Explicit consumer factory: Spring Boot's Kafka autoconfig isn't
     * exposing one in this Boot/Kafka combo. Both key and value go through
     * {@link ErrorHandlingDeserializer} so a malformed payload surfaces as a
     * handler exception (and is routed to the DLT) instead of crashing the
     * container — the delegate classes are inherited from application.yml.
     */
    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template,
                (ConsumerRecord<?, ?> record, Exception ex) ->
                        new TopicPartition("tenants.events.DLT", record.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    }

    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory consumerFactory,
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
