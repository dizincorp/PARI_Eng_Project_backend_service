package pl.pollub.pari.support.domain;

/** Who opened a ticket. */
public enum TicketSource {
    OWNER,
    DRIVER,
    /** Opened by the scheduler when a session is still running after the reserved end. */
    SYSTEM
}
