-- Every notification of the payment provider, with its body exactly as received: an audit trail of the
-- webhooks and the protection against handling the same final notification twice.
CREATE TABLE payment_notifications
(
    id             UUID PRIMARY KEY,
    provider       VARCHAR(20)  NOT NULL,
    event          VARCHAR(64)  NOT NULL,
    object_id      VARCHAR(100) NOT NULL,
    payload        TEXT         NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    transaction_id UUID,
    error          VARCHAR(500),
    received_at    TIMESTAMPTZ  NOT NULL,
    processed_at   TIMESTAMPTZ,
    CONSTRAINT fk_payment_notifications_transaction FOREIGN KEY (transaction_id) REFERENCES payment_transactions (id),
    CONSTRAINT ck_payment_notifications_provider CHECK (provider IN ('MOCK', 'YOOKASSA')),
    CONSTRAINT ck_payment_notifications_status CHECK (status IN
        ('RECEIVED', 'PROCESSED', 'DUPLICATE', 'IGNORED', 'FAILED'))
);

CREATE INDEX idx_payment_notifications_object ON payment_notifications (provider, object_id, event);
CREATE INDEX idx_payment_notifications_transaction ON payment_notifications (transaction_id);
