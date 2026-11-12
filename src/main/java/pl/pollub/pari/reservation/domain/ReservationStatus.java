package pl.pollub.pari.reservation.domain;

import java.util.EnumSet;
import java.util.Set;

public enum ReservationStatus {
    /** Created, waiting for the payment provider to confirm. Holds the slot until the payment times out. */
    PENDING_PAYMENT,
    /** Paid, waiting for the owner's decision (auto-accept is off). */
    PENDING_APPROVAL,
    /** Paid and accepted; PIN and QR token exist. */
    CONFIRMED,
    /** The driver checked in. */
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    REJECTED,
    /** The owner did not accept in time (PARI-38); the driver is refunded. Does not hold the slot. */
    EXPIRED,
    /** Confirmed but the driver never checked in before the reserved end. No refund, the owner is paid, the slot is free. */
    NO_SHOW;

    /** Statuses that keep the time slot taken. Must stay equal to the WHERE clause of the reservations_no_overlap constraint. */
    public static final Set<ReservationStatus> SLOT_HOLDING =
            EnumSet.of(PENDING_PAYMENT, PENDING_APPROVAL, CONFIRMED, IN_PROGRESS);

    public boolean holdsSlot() {
        return SLOT_HOLDING.contains(this);
    }
}
