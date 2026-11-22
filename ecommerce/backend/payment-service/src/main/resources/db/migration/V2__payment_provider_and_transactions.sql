-- Real payment provider (YooKassa) or its local imitation: a payment can take several attempts and
-- refunds are asynchronous, so every operation at the provider is kept in payment_transactions.

-- Card and e-wallet are now chosen on the provider's page: a single ONLINE method
ALTER TABLE payments DROP CONSTRAINT ck_payments_method;
UPDATE payments SET method = 'ONLINE' WHERE method IN ('CARD', 'E_WALLET');
ALTER TABLE payments ADD CONSTRAINT ck_payments_method CHECK (method IN ('COD', 'ONLINE'));

ALTER TABLE payments DROP CONSTRAINT ck_payments_status;
ALTER TABLE payments ADD CONSTRAINT ck_payments_status CHECK (status IN
    ('PENDING', 'SUCCEEDED', 'FAILED', 'REFUND_PENDING', 'REFUNDED', 'REFUND_FAILED', 'CANCELLED'));

ALTER TABLE payments
    ADD COLUMN provider          VARCHAR(20),
    ADD COLUMN current_charge_id UUID,
    ADD COLUMN confirmation_token VARCHAR(255),
    ADD COLUMN attempts          INT NOT NULL DEFAULT 0,
    ADD COLUMN paid_at           TIMESTAMPTZ;

ALTER TABLE payments ADD CONSTRAINT ck_payments_provider CHECK (provider IN ('COD', 'MOCK', 'YOOKASSA', 'LEGACY'));
ALTER TABLE payments ADD CONSTRAINT ck_payments_attempts CHECK (attempts >= 0);

CREATE TABLE payment_transactions
(
    id               UUID PRIMARY KEY,
    payment_id       UUID           NOT NULL,
    type             VARCHAR(10)    NOT NULL,
    provider         VARCHAR(20)    NOT NULL,
    provider_id      VARCHAR(100),
    parent_id        UUID,
    status           VARCHAR(20)    NOT NULL,
    amount           NUMERIC(14, 2) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    confirmation_token VARCHAR(255),
    failure_reason   VARCHAR(500),
    created_at       TIMESTAMPTZ    NOT NULL,
    updated_at       TIMESTAMPTZ    NOT NULL,
    version          BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT fk_payment_transactions_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT fk_payment_transactions_parent FOREIGN KEY (parent_id) REFERENCES payment_transactions (id),
    CONSTRAINT uk_payment_transactions_provider_id UNIQUE (provider, provider_id),
    CONSTRAINT ck_payment_transactions_type CHECK (type IN ('CHARGE', 'REFUND')),
    CONSTRAINT ck_payment_transactions_provider CHECK (provider IN ('COD', 'MOCK', 'YOOKASSA', 'LEGACY')),
    CONSTRAINT ck_payment_transactions_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_payment_transactions_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_transactions_refund_parent CHECK (type = 'CHARGE' OR parent_id IS NOT NULL)
);

CREATE INDEX idx_payment_transactions_payment ON payment_transactions (payment_id, created_at);
-- Operations still waiting for the provider, checked by the reconciliation job
CREATE INDEX idx_payment_transactions_pending ON payment_transactions (created_at) WHERE status = 'PENDING';

-- History of the payments made with the simulated gateway
UPDATE payments SET provider = CASE method WHEN 'COD' THEN 'COD' ELSE 'LEGACY' END WHERE method IS NOT NULL;

INSERT INTO payment_transactions (id, payment_id, type, provider, provider_id, status, amount, currency,
                                  failure_reason, created_at, updated_at)
SELECT gen_random_uuid(), id, 'CHARGE', provider, transaction_id,
       CASE status WHEN 'FAILED' THEN 'FAILED' ELSE 'SUCCEEDED' END,
       amount, currency, CASE status WHEN 'FAILED' THEN failure_reason END, created_at, updated_at
FROM payments
WHERE provider IS NOT NULL AND amount IS NOT NULL;

UPDATE payments p
SET current_charge_id = t.id,
    attempts          = CASE WHEN p.method = 'ONLINE' THEN 1 ELSE 0 END,
    paid_at           = CASE WHEN p.status IN ('SUCCEEDED', 'REFUNDED') THEN p.created_at END
FROM payment_transactions t
WHERE t.payment_id = p.id AND t.type = 'CHARGE';

INSERT INTO payment_transactions (id, payment_id, type, provider, provider_id, parent_id, status, amount, currency,
                                  created_at, updated_at)
SELECT gen_random_uuid(), p.id, 'REFUND', p.provider, p.refund_transaction_id, p.current_charge_id, 'SUCCEEDED',
       p.amount, p.currency, p.refunded_at, p.refunded_at
FROM payments p
WHERE p.status = 'REFUNDED' AND p.refunded_at IS NOT NULL AND p.current_charge_id IS NOT NULL;
