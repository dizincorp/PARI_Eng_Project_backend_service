package pl.pollub.pari.reservation.service;

import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read model of a reservation, built inside the service transaction. The access PIN and QR token are shown only to the
 * driver and only while the reservation is confirmed or running; the owner never sees them.
 */
public record ReservationView(
        UUID id,
        String code,
        UUID spotId,
        String spotName,
        UUID driverId,
        UUID vehicleId,
        String vehiclePlate,
        Instant startsAt,
        Instant endsAt,
        ReservationStatus status,
        BigDecimal hourlyRate,
        BigDecimal spotCost,
        BigDecimal serviceFee,
        BigDecimal total,
        String currency,
        String pinCode,
        String qrToken,
        String cancelReason,
        Instant createdAt,
        Instant checkInAt,
        Instant checkOutAt,
        BigDecimal overstayPenalty,
        BigDecimal finalCost) {

    public static ReservationView of(Reservation r, UUID viewerId) {
        boolean showAccess = r.getDriver().getId().equals(viewerId)
                && (r.getStatus() == ReservationStatus.CONFIRMED || r.getStatus() == ReservationStatus.IN_PROGRESS);
        return new ReservationView(
                r.getId(),
                r.getCode(),
                r.getSpot().getId(),
                r.getSpot().getName(),
                r.getDriver().getId(),
                r.getVehicle().getId(),
                r.getVehicle().getPlateNumber(),
                r.getPeriod().start(),
                r.getPeriod().end(),
                r.getStatus(),
                r.getHourlyRate().toDecimal(),
                r.getSpotCost().toDecimal(),
                r.getServiceFee().toDecimal(),
                r.getTotal().toDecimal(),
                r.getTotal().currency(),
                showAccess ? r.getPinCode() : null,
                showAccess ? r.getQrToken() : null,
                r.getCancelReason(),
                r.getCreatedAt(),
                r.getCheckInAt(),
                r.getCheckOutAt(),
                r.getOverstayPenalty() == null ? null : r.getOverstayPenalty().toDecimal(),
                r.getFinalCost() == null ? null : r.getFinalCost().toDecimal());
    }
}
