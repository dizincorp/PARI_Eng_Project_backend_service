package pl.pollub.pari.reservation.event;

import pl.pollub.pari.reservation.domain.Reservation;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Published (Spring application event) whenever a reservation changes status in a way users should hear about. The
 * notification module turns it into in-app notifications. {@code actorId} is who caused the change (null for the
 * scheduler); {@code pinCode} is set only for events that confirm the booking.
 */
public record ReservationEvent(
        Type type,
        UUID reservationId,
        String code,
        UUID spotId,
        String spotName,
        UUID driverId,
        UUID ownerId,
        UUID actorId,
        String pinCode,
        long overstayMinutes,
        BigDecimal penalty) {

    public enum Type {
        /** Paid, waiting for the owner's decision. */
        REQUESTED,
        /** Paid and confirmed at once (auto-accept). */
        BOOKED,
        /** The owner accepted a request. */
        ACCEPTED,
        REJECTED,
        CANCELLED,
        /** The owner did not answer in time. */
        EXPIRED,
        CHECKED_IN,
        /** Check-out after the grace period; the penalty is above zero. */
        OVERSTAY,
        /** The system ended a session the driver did not end (PARI-47); {@code penalty} may be zero. */
        AUTO_CHECKOUT
    }

    public static ReservationEvent of(Type type, Reservation r, UUID actorId) {
        return new ReservationEvent(type, r.getId(), r.getCode(), r.getSpot().getId(), r.getSpot().getName(),
                r.getDriver().getId(), r.getSpot().getOwner().getId(), actorId,
                type == Type.BOOKED || type == Type.ACCEPTED ? r.getPinCode() : null, 0, null);
    }

    public static ReservationEvent autoCheckout(Reservation r, long minutes, BigDecimal penalty) {
        return new ReservationEvent(Type.AUTO_CHECKOUT, r.getId(), r.getCode(), r.getSpot().getId(), r.getSpot().getName(),
                r.getDriver().getId(), r.getSpot().getOwner().getId(), null, null, minutes, penalty);
    }

    public static ReservationEvent overstay(Reservation r, UUID actorId, long minutes, BigDecimal penalty) {
        return new ReservationEvent(Type.OVERSTAY, r.getId(), r.getCode(), r.getSpot().getId(), r.getSpot().getName(),
                r.getDriver().getId(), r.getSpot().getOwner().getId(), actorId, null, minutes, penalty);
    }
}
