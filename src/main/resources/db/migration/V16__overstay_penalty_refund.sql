-- PARI-100: support can refund an automatic overstay penalty that was already charged.
--
-- The refund itself is an ordinary REFUND payment (the payments ledger is append-only). The reservation remembers which
-- refund belongs to its overstay penalty, so the state is read from the reservation and not guessed from the ledger:
--   overstay_refund_payment_id    the REFUND payment; UNIQUE, so one penalty can be refunded only once even if two
--                                 requests race (the command also locks the reservation row)
--   overstay_refunded_at          when support refunded it
--   overstay_refund_operation_key the idempotency key of the command: the same key again changes nothing
-- All three stay NULL until a refund happens. overstay_penalty_minor and final_cost_minor are not touched: they keep the
-- amount that was billed, and the refund is shown next to them.

ALTER TABLE reservations
    ADD COLUMN overstay_refund_payment_id    uuid REFERENCES payments (id),
    ADD COLUMN overstay_refunded_at          timestamptz,
    ADD COLUMN overstay_refund_operation_key varchar(200);

CREATE UNIQUE INDEX reservations_overstay_refund_payment_uq
    ON reservations (overstay_refund_payment_id) WHERE overstay_refund_payment_id IS NOT NULL;

ALTER TABLE reservations
    ADD CONSTRAINT reservations_overstay_refund_complete CHECK (
        (overstay_refund_payment_id IS NULL AND overstay_refunded_at IS NULL AND overstay_refund_operation_key IS NULL)
        OR (overstay_refund_payment_id IS NOT NULL AND overstay_refunded_at IS NOT NULL AND overstay_refund_operation_key IS NOT NULL));
