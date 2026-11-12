package pl.pollub.pari.support.service;

import pl.pollub.pari.payment.domain.Payment;
import pl.pollub.pari.payment.domain.PaymentStatus;
import pl.pollub.pari.payment.domain.PaymentType;
import pl.pollub.pari.reservation.domain.BookingType;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.user.domain.User;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The reservation of a ticket as support sees it (PARI-81). It identifies the spot and both participants with stable ids so
 * support commands can target the right account. It never contains the PIN, the QR token or any other access secret.
 * Contact data (e-mail, phone) is shown to support only.
 */
public record SupportReservationView(
        UUID reservationId,
        String code,
        ReservationStatus status,
        BookingType bookingType,
        Spot spot,
        Instant startsAt,
        Instant endsAt,
        Instant blockedUntil,
        String timezone,
        String vehiclePlate,
        String vehicleModel,
        Party driver,
        Party owner,
        Lifecycle lifecycle,
        List<PaymentLine> payments,
        String refundState) {

    public record Spot(UUID id, String name, String street, String city, String postalCode, String country) {
    }

    /** {@code id} is the account id to use in support commands such as a sanction. */
    public record Party(UUID id, String firstName, String lastName, String displayName, String email, String phoneNumber) {

        static Party of(User u) {
            return new Party(u.getId(), u.getFirstName(), u.getLastName(), u.getFirstName() + " " + u.getLastName(),
                    u.getEmail(), u.getPhone());
        }
    }

    /** Check-in and check-out times, the amounts, the penalty and how the session ended. */
    public record Lifecycle(Instant checkInAt, Instant checkOutAt, boolean autoCheckedOut, boolean penaltyPending,
                            BigDecimal overstayPenalty, BigDecimal spotCost, BigDecimal serviceFee, BigDecimal total,
                            BigDecimal finalCost, String currency, String cancelReason) {
    }

    public record PaymentLine(PaymentType type, PaymentStatus status, BigDecimal amount, String currency, Instant createdAt) {
    }

    /**
     * NONE: no refund. REFUNDED: a refund was paid. PENDING_OR_FAILED: a refund exists but did not succeed. Derived from
     * the REFUND payments of the reservation.
     */
    static String refundState(List<Payment> payments) {
        List<Payment> refunds = payments.stream().filter(p -> p.getType() == PaymentType.REFUND).toList();
        if (refunds.isEmpty()) {
            return "NONE";
        }
        return refunds.stream().anyMatch(p -> p.getStatus() == PaymentStatus.SUCCEEDED) ? "REFUNDED" : "PENDING_OR_FAILED";
    }

    static SupportReservationView of(Reservation r, List<Payment> payments, String timezone) {
        var a = r.getSpot().getAddress();
        Lifecycle lifecycle = new Lifecycle(r.getCheckInAt(), r.getCheckOutAt(), r.isAutoCheckedOut(), r.isPenaltyPending(),
                r.getOverstayPenalty() == null ? null : r.getOverstayPenalty().toDecimal(), r.getSpotCost().toDecimal(),
                r.getServiceFee().toDecimal(), r.getTotal().toDecimal(),
                r.getFinalCost() == null ? null : r.getFinalCost().toDecimal(), r.getTotal().currency(), r.getCancelReason());
        return new SupportReservationView(r.getId(), r.getCode(), r.getStatus(), r.getBookingType(),
                new Spot(r.getSpot().getId(), r.getSpot().getName(), a.street(), a.city(), a.postalCode(), a.country()),
                r.getPeriod().start(), r.getPeriod().end(), r.getBlockedUntil(), timezone,
                r.getVehicle().getPlateNumber(), r.getVehicle().getModel(), Party.of(r.getDriver()),
                Party.of(r.getSpot().getOwner()), lifecycle,
                payments.stream().map(p -> new PaymentLine(p.getType(), p.getStatus(), p.getAmount().toDecimal(),
                        p.getAmount().currency(), p.getCreatedAt())).toList(),
                refundState(payments));
    }
}
