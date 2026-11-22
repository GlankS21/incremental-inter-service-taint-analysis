-- order.created is delivered at least once: the id of every handled event is kept so that a redelivered
-- event does not subtract the ordered quantities from the cart a second time.

CREATE TABLE processed_events
(
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
