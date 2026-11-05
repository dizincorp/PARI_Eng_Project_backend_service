package pl.pollub.pari.reservation.service;

import pl.pollub.pari.reservation.domain.BookingType;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.user.domain.User;

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
        String ownerFirstName,
        String ownerLastName,
        String ownerPhoneNumber,
        String ownerAvatarUrl,
        UUID driverId,
        String driverFirstName,
        String driverLastName,
        String driverPhoneNumber,
        String driverAvatarUrl,
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
        BookingType bookingType,
        Integer durationDays,
        Integer durationMonths,
        BigDecimal baseCost,
        BigDecimal discountAmount,
        String pinCode,
        String qrToken,
        String cancelReason,
        Instant createdAt,
        Instant checkInAt,
        Instant checkOutAt,
        BigDecimal overstayPenalty,
        BigDecimal finalCost) {

    public static ReservationView of(Reservation r, UUID viewerId) {
        User owner = r.getSpot().getOwner();
        User driver = r.getDriver();
        boolean showAccess = driver.getId().equals(viewerId)
                && (r.getStatus() == ReservationStatus.CONFIRMED || r.getStatus() == ReservationStatus.IN_PROGRESS);
        // the phone numbers go to the two parties of the reservation only
        boolean participant = driver.getId().equals(viewerId) || owner.getId().equals(viewerId);
        return new ReservationView(
                r.getId(),
                r.getCode(),
                r.getSpot().getId(),
                r.getSpot().getName(),
                owner.getFirstName(),
                owner.getLastName(),
                participant ? owner.getPhone() : null,
                owner.getAvatarUrl(),
                driver.getId(),
                driver.getFirstName(),
                driver.getLastName(),
                participant ? driver.getPhone() : null,
                driver.getAvatarUrl(),
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
                r.getBookingType(),
                r.getDurationDays(),
                r.getDurationMonths(),
                (r.getBaseCost() == null ? r.getSpotCost() : r.getBaseCost()).toDecimal(),
                r.getDiscount().toDecimal(),
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
