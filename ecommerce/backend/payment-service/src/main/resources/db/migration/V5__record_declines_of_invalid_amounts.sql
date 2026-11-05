-- A payment request without a positive amount or a currency is declined before any attempt: the payment
-- is stored as FAILED with the reason, and payment.failed is queued in the same transaction so that
-- order-service cancels the order. The old constraint rejected that row, so the decline was rolled back
-- and the request ended in the dead-letter queue. An attempt at the provider still needs a valid amount:
-- a declined request never gets a charge (retrying it is refused).
ALTER TABLE payments DROP CONSTRAINT ck_payments_amount;
ALTER TABLE payments ADD CONSTRAINT ck_payments_amount CHECK (
    status = 'CANCELLED'
    OR (status = 'FAILED' AND current_charge_id IS NULL)
    OR (amount > 0 AND currency IS NOT NULL));
