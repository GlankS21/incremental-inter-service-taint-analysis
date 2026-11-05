-- Card and e-wallet are now chosen on the payment provider's page: a single ONLINE method.
-- Paid orders can be cancelled until shipped; the refund of their payment is recorded on the order.

ALTER TABLE orders DROP CONSTRAINT ck_orders_payment_method;
UPDATE orders SET payment_method = 'ONLINE' WHERE payment_method IN ('CARD', 'E_WALLET');
ALTER TABLE orders ADD CONSTRAINT ck_orders_payment_method CHECK (payment_method IN ('COD', 'ONLINE'));

ALTER TABLE orders
    ADD COLUMN refund_transaction_id VARCHAR(100),
    ADD COLUMN refunded_at           TIMESTAMPTZ;

ALTER TABLE orders ADD CONSTRAINT ck_orders_refund_of_cancelled CHECK (refunded_at IS NULL OR status = 'CANCELLED');
