package com.uptimecrew.multistate.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Polling-side access for the outbox.
 *
 * <p>{@link #findUnpublishedForUpdate(Pageable)} uses a native query with
 * {@code FOR UPDATE SKIP LOCKED} so two publisher instances polling the
 * same table never serialize on the same rows: each transaction grabs a
 * disjoint batch, the other instance's batch is invisible until commit,
 * and a failed/slow publisher doesn't block its peers. Plain
 * {@code FOR UPDATE} (without {@code SKIP LOCKED}) is a blocking lock —
 * the second publisher would wait on the first publisher's row locks and
 * lose the concurrency it wants.
 *
 * <p>Filtering on {@code published_at IS NULL} aligns with the partial
 * index {@code idx_event_outbox_unpublished} so the scan stays cheap as
 * the table grows.
 */
@Repository
public interface EventOutboxRepository extends JpaRepository<EventOutboxEntity, UUID> {

    @Query(value = """
        SELECT * FROM multistate.event_outbox
        WHERE published_at IS NULL
        ORDER BY occurred_at ASC
        FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true)
    List<EventOutboxEntity> findUnpublishedForUpdate(Pageable pageable);
}