-- PARI-86: a notification can point at a support ticket and at one reply in it, so the client can route to the right
-- ticket after a reload and read state can be tracked per ticket.
--
-- Existing rows keep NULL in both columns. The unique index makes the support-reply notification idempotent: one
-- notification per recipient and reply, however often the event is delivered.

ALTER TABLE notifications
    ADD COLUMN ticket_id  uuid REFERENCES reports (id) ON DELETE CASCADE,
    ADD COLUMN message_id uuid REFERENCES report_messages (id) ON DELETE CASCADE;

CREATE UNIQUE INDEX notifications_user_message_uq ON notifications (user_id, message_id) WHERE message_id IS NOT NULL;
CREATE INDEX notifications_user_ticket_idx ON notifications (user_id, ticket_id) WHERE ticket_id IS NOT NULL;
