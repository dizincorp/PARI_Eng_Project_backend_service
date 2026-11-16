-- PARI-101: support can refund a manual (assessed) penalty that was charged.
--
-- A refunded penalty keeps its amount, its charge payment (payment_id) and its decision (decided_by/at/reason), so the charge
-- history is preserved. The refund is an ordinary REFUND payment (the ledger is append-only) and is linked here:
--   refund_payment_id     the REFUND payment; UNIQUE, so one penalty is refunded at most once, even under a race
--   refund_operation_key  idempotency key of the command; UNIQUE, the same key again changes nothing
--   refunded_at / refunded_by / refund_reason  when, by whom and why it was returned
-- All five stay NULL until a refund happens.

ALTER TABLE support_penalties DROP CONSTRAINT support_penalties_state_check;
ALTER TABLE support_penalties ADD CONSTRAINT support_penalties_state_check
    CHECK (state IN ('PENDING', 'CHARGED', 'WAIVED', 'FAILED', 'REFUNDED'));

ALTER TABLE support_penalties
    ADD COLUMN refund_payment_id    uuid REFERENCES payments (id),
    ADD COLUMN refund_operation_key varchar(200),
    ADD COLUMN refunded_at          timestamptz,
    ADD COLUMN refunded_by          uuid REFERENCES users (id),
    ADD COLUMN refund_reason        text;

CREATE UNIQUE INDEX support_penalties_refund_payment_uq ON support_penalties (refund_payment_id) WHERE refund_payment_id IS NOT NULL;
CREATE UNIQUE INDEX support_penalties_refund_key_uq ON support_penalties (refund_operation_key) WHERE refund_operation_key IS NOT NULL;

ALTER TABLE support_penalties
    ADD CONSTRAINT support_penalties_refund_complete CHECK (
        (state = 'REFUNDED') = (refund_payment_id IS NOT NULL)
        AND (refund_payment_id IS NULL) = (refund_operation_key IS NULL)
        AND (refund_payment_id IS NULL) = (refunded_at IS NULL)
        AND (refund_payment_id IS NULL) = (refunded_by IS NULL)
        AND (refund_payment_id IS NULL) = (refund_reason IS NULL));
