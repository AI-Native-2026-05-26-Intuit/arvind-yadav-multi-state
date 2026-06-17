package com.uptimecrew.multistate.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polls multistate.event_outbox once a second and forwards unpublished rows
 * to Kafka. The whole publish loop runs in one @Transactional method so the
 * row-level locks acquired by {@code FOR UPDATE SKIP LOCKED} are held until
 * commit — concurrent publishers see disjoint batches and never duplicate a
 * row.
 *
 * <p>Send is synchronous with a hard 5-second cap per row: the publisher
 * doesn't fire-and-forget, so a transient Kafka failure leaves the row
 * unpublished (no {@code published_at} stamp) and the next poll retries it.
 * The Kafka message key is the aggregate id, which keeps all events for the
 * same aggregate on one partition (per-aggregate ordering).
 */
@Component
// Not final: @Transactional + @Scheduled cause Spring to wrap this bean in a CGLIB
// proxy, which must subclass it. Justified exception to "classes default to final".
public class OutboxPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 50;
    private static final long SEND_TIMEOUT_SECONDS = 5L;

    private final EventOutboxRepository repository;
    // Raw KafkaTemplate: Spring Boot's autoconfig publishes the bean as
    // KafkaTemplate<Object, Object>; we narrow at send-time by passing
    // String key/value — generic params here would prevent that bean from
    // matching during constructor injection.
    @SuppressWarnings("rawtypes")
    private final KafkaTemplate kafkaTemplate;

    public OutboxPublisher(EventOutboxRepository repository,
                           @SuppressWarnings("rawtypes") KafkaTemplate kafkaTemplate) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate");
    }

    @Scheduled(fixedDelay = 1000L)
    @Transactional
    public void publishPending() {
        List<EventOutboxEntity> batch =
                repository.findUnpublishedForUpdate(PageRequest.of(0, BATCH_SIZE));
        if (batch.isEmpty()) {
            return;
        }
        for (EventOutboxEntity row : batch) {
            try {
                @SuppressWarnings("unchecked")
                var future = kafkaTemplate.send(row.getTopic(), row.getAggregateId(), row.getPayload());
                future.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                row.markPublished(Instant.now());
                LOG.info("outbox published id={} topic={} aggregateId={}",
                         row.getId(), row.getTopic(), row.getAggregateId());
            } catch (Exception ex) {
                // Leave published_at NULL so the next poll retries this row.
                // Don't rethrow: one bad row shouldn't poison the rest of the batch.
                LOG.warn("outbox publish failed id={} topic={} cause={}",
                         row.getId(), row.getTopic(), ex.toString());
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}