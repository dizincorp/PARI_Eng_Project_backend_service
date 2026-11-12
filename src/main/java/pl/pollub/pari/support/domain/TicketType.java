package pl.pollub.pari.support.domain;

/** What a support ticket is about (PARI-70). */
public enum TicketType {
    /** The owner says the car is still on the spot after the reservation. High priority. */
    VEHICLE_NOT_LEFT,
    /** The driver says they cannot leave the car park. High priority. */
    CANNOT_LEAVE,
    OTHER;

    public TicketPriority defaultPriority() {
        return this == OTHER ? TicketPriority.NORMAL : TicketPriority.HIGH;
    }
}
