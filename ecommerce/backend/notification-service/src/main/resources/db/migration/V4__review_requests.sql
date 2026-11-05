-- Review requests: «Оцените покупку» in the account when an order is delivered (REVIEW_REQUEST, in-app
-- only) and one reminder e-mail per order a few days later (REVIEW_REMINDER), which customers can turn off.

ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;

ALTER TABLE notifications
    ADD CONSTRAINT ck_notifications_type
        CHECK (type IN ('WELCOME', 'PASSWORD_RESET', 'ORDER_PLACED', 'ORDER_CONFIRMED', 'ORDER_CANCELLED',
                        'REVIEW_REQUEST', 'REVIEW_REMINDER'));

-- One row per customer who changed a setting; no row means the defaults (every reminder on)
CREATE TABLE notification_preferences
(
    user_id          UUID PRIMARY KEY,
    review_reminders BOOLEAN     NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL
);
