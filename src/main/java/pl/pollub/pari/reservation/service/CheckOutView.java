package pl.pollub.pari.reservation.service;

import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Answer to a check-out (PARI-37). {@code overstayMinutes} is the time past the reserved end, also when it stayed inside
 * the grace period (then the penalty is 0). {@code baseCost} is the booked spot cost without the service fee;
 * {@code totalFinalAmount} = baseCost + overstayPenaltyFee.
 */
public record CheckOutView(
        UUID id,
        String code,
        ReservationStatus status,
        Instant actualCheckoutAt,
        Instant plannedEndsAt,
        long overstayMinutes,
        BigDecimal baseCost,
        BigDecimal overstayPenaltyFee,
        BigDecimal totalFinalAmount,
        String currency) {

    public static CheckOutView of(Reservation r) {
        BigDecimal penalty = r.getOverstayPenalty() == null ? BigDecimal.ZERO.setScale(2) : r.getOverstayPenalty().toDecimal();
        return new CheckOutView(r.getId(), r.getCode(), r.getStatus(), r.getCheckOutAt(), r.getPeriod().end(),
                r.overstayMinutes(), r.getSpotCost().toDecimal(), penalty, r.getFinalCost().toDecimal(),
                r.getFinalCost().currency());
    }
}
