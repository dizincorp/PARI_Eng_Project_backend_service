package pl.pollub.pari.payment.domain;

public enum PayoutStatus {
    /** Waiting for release_at (reservation end + 24 h). */
    SCHEDULED,
    /** Paused because a report on the reservation is open. */
    HELD,
    PAID,
    CANCELLED
}
