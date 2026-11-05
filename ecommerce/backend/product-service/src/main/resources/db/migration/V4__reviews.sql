-- Product reviews: only customers with a DELIVERED order containing the product may review it.
-- The rating summary is denormalised onto products so that listings can show and sort by it.

ALTER TABLE products
    ADD COLUMN rating_average NUMERIC(2, 1) NOT NULL DEFAULT 0,
    ADD COLUMN rating_count   INT           NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_products_rating_average CHECK (rating_average >= 0 AND rating_average <= 5),
    ADD CONSTRAINT ck_products_rating_count CHECK (rating_count >= 0);

CREATE INDEX idx_products_rating_average ON products (rating_average DESC);

CREATE TABLE product_reviews
(
    id          BIGSERIAL PRIMARY KEY,
    product_id  BIGINT        NOT NULL,
    user_id     UUID          NOT NULL,
    author_name VARCHAR(60)   NOT NULL,
    rating      SMALLINT      NOT NULL,
    comment     VARCHAR(2000),
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,
    version     BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uk_product_reviews_product_user UNIQUE (product_id, user_id),
    CONSTRAINT fk_product_reviews_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE CASCADE,
    CONSTRAINT ck_product_reviews_rating CHECK (rating BETWEEN 1 AND 5)
);

CREATE INDEX idx_product_reviews_product_created ON product_reviews (product_id, created_at DESC);
CREATE INDEX idx_product_reviews_created ON product_reviews (created_at DESC);

-- One row per (customer, product, delivered order), filled from order.delivered; redeliveries are ignored
CREATE TABLE product_purchases
(
    id           BIGSERIAL PRIMARY KEY,
    user_id      UUID        NOT NULL,
    product_id   BIGINT      NOT NULL,
    order_id     UUID        NOT NULL,
    purchased_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_product_purchases_user_product_order UNIQUE (user_id, product_id, order_id),
    CONSTRAINT fk_product_purchases_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE CASCADE
);

CREATE INDEX idx_product_purchases_product ON product_purchases (product_id);
