-- New notification type: password reset link (user.password-reset-requested)

ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;

ALTER TABLE notifications
    ADD CONSTRAINT ck_notifications_type
        CHECK (type IN ('WELCOME', 'PASSWORD_RESET', 'ORDER_PLACED', 'ORDER_CONFIRMED', 'ORDER_CANCELLED'));
