-- Review requests: pending reviews of a customer, verified purchase flag, one reminder per delivered order.

-- A review can only be written by a customer who received the product (review.error.not-purchased),
-- so every existing review is a verified purchase.
ALTER TABLE product_reviews
    ADD COLUMN verified_purchase BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX idx_product_reviews_user_updated ON product_reviews (user_id, updated_at DESC);

-- order_number: shown in the reminder e-mail. review_reminder_processed_at: when the reminder job
-- looked at the order (whether a reminder was due or not); a processed order is never reminded again.
ALTER TABLE product_purchases
    ADD COLUMN order_number                 VARCHAR(40),
    ADD COLUMN review_reminder_processed_at TIMESTAMPTZ;

-- Deliveries before this migration are not reminded: their customers never got the in-app request
-- either, and a first run must not e-mail every past buyer at once.
UPDATE product_purchases SET review_reminder_processed_at = now();

CREATE INDEX idx_product_purchases_user_purchased ON product_purchases (user_id, purchased_at DESC);
CREATE INDEX idx_product_purchases_order ON product_purchases (order_id);
CREATE INDEX idx_product_purchases_reminder_due ON product_purchases (purchased_at)
    WHERE review_reminder_processed_at IS NULL;
