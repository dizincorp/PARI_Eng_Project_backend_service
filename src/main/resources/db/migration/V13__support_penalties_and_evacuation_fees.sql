-- PARI-87 / PARI-88: support-assessed penalties and itemized evacuation fees.
--
-- support_penalties: one row per penalty a support employee assessed on a ticket's reservation. PENDING until support
-- charges or waives it; CHARGED or FAILED mirror the payment outcome. Existing overstay penalties stay on reservations.
-- evacuation_fees: append-only items. A FEE row is a charge for an evacuation; a REFUND row reverses one FEE row in full
-- (refunds_fee_id), so nothing is ever overwritten. The total is the sum of CHARGED fees minus the refunds.
-- operation_key is unique, so a retried command cannot create a second record or a second payment.
-- Evacuation fees get their own payment type, so they are no longer mixed into PENALTY payments.

ALTER TABLE payments DROP CONSTRAINT payments_type_check;
ALTER TABLE payments ADD CONSTRAINT payments_type_check CHECK (type IN ('CHARGE', 'PENALTY', 'REFUND', 'EVAC_FEE'));

CREATE TABLE support_penalties (
    id               uuid         PRIMARY KEY,
    reservation_id   uuid         NOT NULL REFERENCES reservations (id),
    ticket_id        uuid         NOT NULL REFERENCES reports (id),
    target_user_id   uuid         NOT NULL REFERENCES users (id),
    amount_minor     bigint       NOT NULL CHECK (amount_minor > 0),
    reason           text         NOT NULL,
    state            varchar(10)  NOT NULL CHECK (state IN ('PENDING', 'CHARGED', 'WAIVED', 'FAILED')),
    operation_key    varchar(200) NOT NULL UNIQUE,
    payment_id       uuid         REFERENCES payments (id),
    assessed_by      uuid         NOT NULL REFERENCES users (id),
    decided_by       uuid         REFERENCES users (id),
    decided_at       timestamptz,
    decision_reason  text,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    version          bigint       NOT NULL DEFAULT 0
);
CREATE INDEX support_penalties_reservation_idx ON support_penalties (reservation_id);
CREATE INDEX support_penalties_ticket_idx ON support_penalties (ticket_id);

CREATE TABLE evacuation_fees (
    id               uuid         PRIMARY KEY,
    reservation_id   uuid         NOT NULL REFERENCES reservations (id),
    ticket_id        uuid         NOT NULL REFERENCES reports (id),
    kind             varchar(10)  NOT NULL CHECK (kind IN ('FEE', 'REFUND')),
    amount_minor     bigint       NOT NULL CHECK (amount_minor > 0),
    status           varchar(10)  NOT NULL CHECK (status IN ('CHARGED', 'FAILED', 'REFUNDED')),
    reason           text         NOT NULL,
    operation_key    varchar(200) NOT NULL UNIQUE,
    payment_id       uuid         REFERENCES payments (id),
    refunds_fee_id   uuid         REFERENCES evacuation_fees (id),
    created_by       uuid         NOT NULL REFERENCES users (id),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    version          bigint       NOT NULL DEFAULT 0,
    CHECK ((kind = 'REFUND') = (refunds_fee_id IS NOT NULL))
);
CREATE INDEX evacuation_fees_reservation_idx ON evacuation_fees (reservation_id);
-- a fee can be refunded at most once
CREATE UNIQUE INDEX evacuation_fees_one_refund_uq ON evacuation_fees (refunds_fee_id) WHERE refunds_fee_id IS NOT NULL;
