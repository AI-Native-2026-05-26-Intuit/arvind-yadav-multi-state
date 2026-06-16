-- V3__event_outbox.sql
-- W3 D3: transactional-outbox table written alongside domain rows in the
-- same JPA transaction. OutboxPublisher polls this table once a second and
-- forwards unpublished rows to Kafka.

CREATE SCHEMA IF NOT EXISTS multistate;
SET search_path TO multistate, public;

-- gen_random_uuid() lives in pgcrypto. Safe to call repeatedly.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS multistate.event_outbox (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Domain aggregate this event was emitted for; reused as the Kafka
    -- message key so all events for the same aggregate land on the same
    -- partition (key-based partitioning preserves per-aggregate ordering).
    aggregate_id  TEXT         NOT NULL,

    -- Kafka topic the publisher will send to.
    topic         TEXT         NOT NULL,

    -- Event body as JSONB so it can be queried/inspected in-DB without an
    -- application-side decode; the publisher just forwards the text.
    payload       JSONB        NOT NULL,

    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- NULL = not yet sent to Kafka. Set on successful publish; left NULL on
    -- failure so the next poll picks the row up again.
    published_at  TIMESTAMPTZ  NULL
);

-- Partial index keeps the polling query cheap as the table grows: only
-- unpublished rows are visited by OutboxPublisher.publishPending().
CREATE INDEX IF NOT EXISTS idx_event_outbox_unpublished
    ON multistate.event_outbox (occurred_at)
    WHERE published_at IS NULL;