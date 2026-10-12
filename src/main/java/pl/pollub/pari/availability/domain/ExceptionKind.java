package pl.pollub.pari.availability.domain;

public enum ExceptionKind {
    /** The owner blocked this period although the weekly rules would allow bookings. */
    BLOCKED,
    /** The owner opened an extra period outside the weekly rules. */
    OPEN
}
