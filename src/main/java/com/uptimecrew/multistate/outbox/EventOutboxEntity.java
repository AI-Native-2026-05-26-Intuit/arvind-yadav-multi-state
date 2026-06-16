package com.uptimecrew.multistate.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row in multistate.event_outbox — a single domain event awaiting
 * publication to Kafka. Inserted in the SAME @Transactional method that
 * writes the domain entity, so the event is committed atomically with the
 * business state. {@link OutboxPublisher} reads unpublished rows, sends
 * them to Kafka keyed by {@code aggregateId} (preserves per-aggregate
 * ordering), and stamps {@code publishedAt} on success.
 */
@Entity
@Table(name = "event_outbox", schema = "multistate")
public final class EventOutboxEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private String aggregateId;

    @Column(name = "topic", nullable = false, updatable = false)
    private String topic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected EventOutboxEntity() { /* JPA only */ }

    public EventOutboxEntity(String aggregateId, String topic, String payload) {
        this.id = UUID.randomUUID();
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.occurredAt = Instant.now();
    }

    public UUID getId()             { return id; }
    public String getAggregateId()  { return aggregateId; }
    public String getTopic()        { return topic; }
    public String getPayload()      { return payload; }
    public Instant getOccurredAt()  { return occurredAt; }
    public Instant getPublishedAt() { return publishedAt; }

    public void markPublished(Instant when) {
        this.publishedAt = Objects.requireNonNull(when, "when");
    }
}