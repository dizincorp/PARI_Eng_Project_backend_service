package pl.pollub.pari.reservation.domain;

/**
 * How a space is booked (PARI-46). HOURLY takes any start and end; DAILY takes whole days; MONTHLY takes a start and a
 * number of months and may get a long-term discount. All of them are priced from the hourly rate of the space.
 */
public enum BookingType {
    HOURLY,
    DAILY,
    MONTHLY
}
