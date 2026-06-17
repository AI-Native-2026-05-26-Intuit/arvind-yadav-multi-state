package com.uptimecrew.multistate.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uptimecrew.multistate.readmodel.TenantReadModel;
import com.uptimecrew.multistate.readmodel.TenantReadModelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code tenants.events} (the topic the outbox publisher writes to)
 * and re-projects each event into the Mongo read model. Idempotent: the same
 * event applied twice produces the same document, so the at-least-once
 * delivery semantics from {@link org.springframework.kafka.core.KafkaTemplate}
 * are safe.
 *
 * <p>A Jackson parse failure (poison pill) is surfaced as an exception by the
 * {@code ErrorHandlingDeserializer} wrapper configured in
 * {@code application.yml}, which lets {@link KafkaErrorHandlingConfig}'s
 * {@code DefaultErrorHandler} route the bad payload to the DLT instead of
 * crashing the container.
 */
@Component
public class AllocationCreatedListener {

    private static final Logger LOG = LoggerFactory.getLogger(AllocationCreatedListener.class);

    private final TenantReadModelRepository readModelRepository;
    private final ObjectMapper mapper;

    public AllocationCreatedListener(TenantReadModelRepository readModelRepository,
                                     ObjectMapper mapper) {
        this.readModelRepository = readModelRepository;
        this.mapper = mapper;
    }

    @KafkaListener(
            topics = "tenants.events",
            groupId = "multistate-read-model-builder",
            containerFactory = "kafkaListenerContainerFactory")
    public void onEvent(String payload) throws Exception {
        AllocationCreatedEvent event = mapper.readValue(payload, AllocationCreatedEvent.class);
        TenantReadModel document = readModelRepository.findById(event.aggregateId())
                .orElseGet(() -> new TenantReadModel(event.aggregateId()));
        document.applyEvent(event);
        readModelRepository.save(document);
        LOG.info("consumed AllocationCreated aggregateId={}", event.aggregateId());
    }
}
