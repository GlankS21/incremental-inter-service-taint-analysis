-- An outbox row that can never be published (its event type no longer exists, or its payload no longer
-- matches the event record) is parked with failed_at instead of being retried forever, so that it no
-- longer blocks the events queued after it. Parked rows stay in the table for investigation.

ALTER TABLE outbox_events ADD COLUMN failed_at TIMESTAMPTZ;

DROP INDEX idx_outbox_events_pending;
CREATE INDEX idx_outbox_events_pending ON outbox_events (created_at) WHERE published_at IS NULL AND failed_at IS NULL;
CREATE INDEX idx_outbox_events_failed ON outbox_events (failed_at) WHERE failed_at IS NOT NULL;
