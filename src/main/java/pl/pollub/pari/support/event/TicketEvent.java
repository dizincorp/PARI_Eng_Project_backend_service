package pl.pollub.pari.support.event;

import pl.pollub.pari.support.domain.Report;
import pl.pollub.pari.support.domain.TicketType;

import java.util.UUID;

/** Published when something happens to a support ticket that users should hear about (PARI-73). */
public record TicketEvent(Type type, UUID ticketId, TicketType ticketType, UUID reservationId, String reservationCode,
                          UUID spotId, String spotName, UUID driverId, UUID ownerId, String detail,
                          String unitLabel, boolean multiUnit) {

    public enum Type {
        CREATED,
        /** An evacuation was requested; {@code detail} is the destination. */
        EVACUATION_REQUESTED,
        /** The evacuation status changed; {@code detail} is the new status. */
        EVACUATION_UPDATED
    }

    public static TicketEvent of(Type type, Report t, String detail) {
        var r = t.getReservation();
        return new TicketEvent(type, t.getId(), t.getType(), r.getId(), r.getCode(), r.getSpot().getId(),
                r.getSpot().getName(), r.getDriver().getId(), r.getSpot().getOwner().getId(), detail,
                r.getUnitLabel(), r.getSpot().liveUnits().size() > 1);
    }
}
