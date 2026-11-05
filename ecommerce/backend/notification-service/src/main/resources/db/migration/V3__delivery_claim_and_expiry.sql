-- SENDING: a delivery attempt owns the row, so that two senders never deliver the same e-mail.
-- EXPIRED: the e-mail held a link that expired before it could be sent (password reset).

ALTER TABLE notifications DROP CONSTRAINT ck_notifications_status;

ALTER TABLE notifications
    ADD CONSTRAINT ck_notifications_status
        CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'EXPIRED'));

-- End of validity of the link in the e-mail; null when the e-mail never expires
ALTER TABLE notifications
    ADD COLUMN expires_at TIMESTAMPTZ;
