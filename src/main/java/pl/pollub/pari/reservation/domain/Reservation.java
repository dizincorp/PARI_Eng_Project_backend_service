package pl.pollub.pari.reservation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import pl.pollub.pari.common.domain.BaseEntity;
import pl.pollub.pari.common.domain.IllegalStateTransitionException;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.domain.TimeRange;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * A booking of a parking space for a time range. Prices are copied at booking time, so later changes of the spot price
 * do not change an existing reservation. All status changes go through the methods below; any other transition
 * throws {@link IllegalStateTransitionException}.
 */
@Entity
@Table(name = "reservations")
@Getter
public class Reservation extends BaseEntity {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Human readable id, for example RSV-20261012-8F3K2. */
    @Column(nullable = false, updatable = false)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "spot_id", nullable = false)
    private ParkingSpot spot;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", nullable = false)
    private User driver;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Embedded
    private TimeRange period;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    @Column(name = "hourly_rate_minor", nullable = false)
    private Money hourlyRate;

    @Column(name = "spot_cost_minor", nullable = false)
    private Money spotCost;

    @Column(name = "service_fee_minor", nullable = false)
    private Money serviceFee;

    @Column(name = "total_minor", nullable = false)
    private Money total;

    /** Platform commission taken from the owner's share (F18). */
    @Column(name = "commission_minor", nullable = false)
    private Money commission;

    /** What the reservation was priced as (PARI-55). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingType bookingType = BookingType.HOURLY;

    /** Calendar days billed, for a DAILY booking. */
    private Integer durationDays;

    /** Months billed, for a MONTHLY booking. */
    private Integer durationMonths;

    /** Cost before the long-term discount; {@code spotCost = baseCost - discount}. */
    @Column(name = "base_cost_minor")
    private Money baseCost;

    @Column(name = "discount_minor", nullable = false)
    private Money discount = Money.ZERO;

    private String pinCode;

    private String qrToken;

    private Instant checkInAt;

    private Instant checkOutAt;

    /** Extra fee for staying longer than reserved (F19). */
    @Column(name = "overstay_penalty_minor")
    private Money overstayPenalty;

    @Column(name = "final_cost_minor")
    private Money finalCost;

    private String cancelReason;

    private Instant cancelledAt;

    protected Reservation() {
    }

    public Reservation(String code, ParkingSpot spot, User driver, Vehicle vehicle, TimeRange period,
                       Money hourlyRate, Money spotCost, Money serviceFee, Money commission) {
        this.code = code;
        this.spot = spot;
        this.driver = driver;
        this.vehicle = vehicle;
        this.period = period;
        this.hourlyRate = hourlyRate;
        this.spotCost = spotCost;
        this.serviceFee = serviceFee;
        this.total = spotCost.add(serviceFee);
        this.commission = commission;
        this.baseCost = spotCost;
        this.status = ReservationStatus.PENDING_PAYMENT;
    }

    /** A reservation with the pricing breakdown of its rental type (PARI-55). */
    public Reservation(String code, ParkingSpot spot, User driver, Vehicle vehicle, TimeRange period,
                       Money hourlyRate, Money spotCost, Money serviceFee, Money commission,
                       BookingType bookingType, Integer durationDays, Integer durationMonths, Money baseCost, Money discount) {
        this(code, spot, driver, vehicle, period, hourlyRate, spotCost, serviceFee, commission);
        this.bookingType = bookingType;
        this.durationDays = durationDays;
        this.durationMonths = durationMonths;
        this.baseCost = baseCost;
        this.discount = discount;
    }

    /** The payment provider confirmed the payment. */
    public void markPaid(boolean autoAccept) {
        requireStatus("be marked as paid", ReservationStatus.PENDING_PAYMENT);
        if (autoAccept) {
            confirm();
        } else {
            this.status = ReservationStatus.PENDING_APPROVAL;
        }
    }

    /** The owner accepts a request. */
    public void accept() {
        requireStatus("be accepted", ReservationStatus.PENDING_APPROVAL);
        confirm();
    }

    /** The owner declines a request; the driver is refunded by the service. */
    public void reject(String reason, Instant now) {
        requireStatus("be rejected", ReservationStatus.PENDING_APPROVAL);
        this.status = ReservationStatus.REJECTED;
        this.cancelReason = reason;
        this.cancelledAt = now;
    }

    /** The owner did not decide in time; the driver is refunded by the service. */
    public void expire(Instant now) {
        requireStatus("expire", ReservationStatus.PENDING_APPROVAL);
        this.status = ReservationStatus.EXPIRED;
        this.cancelReason = "The owner did not respond in time";
        this.cancelledAt = now;
    }

    /** True when the given PIN or QR token is the one issued for this reservation. */
    public boolean matchesAccessCode(String pin, String qr) {
        return (pin != null && pin.equals(pinCode)) || (qr != null && qr.equals(qrToken));
    }

    /** How long before the reserved start the driver may already check in (PARI-61). */
    public static final java.time.Duration EARLY_CHECK_IN = java.time.Duration.ofMinutes(5);

    /** The first moment check-in is accepted: {@link #EARLY_CHECK_IN} before the reserved start. */
    public Instant earliestCheckIn() {
        return period.start().minus(EARLY_CHECK_IN);
    }

    /**
     * Check-in is possible from {@link #EARLY_CHECK_IN} before the start until the end of the reserved period. The
     * booked interval and the amount do not change when the driver arrives early.
     */
    public void checkIn(Instant now) {
        requireStatus("be checked in", ReservationStatus.CONFIRMED);
        if (now.isBefore(earliestCheckIn()) || !now.isBefore(period.end())) {
            throw new IllegalStateTransitionException("check-in is possible only between " + earliestCheckIn() + " and " + period.end());
        }
        this.status = ReservationStatus.IN_PROGRESS;
        this.checkInAt = now;
    }

    /** Sets the fee for exceeding the reserved time. Call before {@link #checkOut(Instant)} so it enters the final cost. */
    public void applyOverstayPenalty(Money penalty) {
        requireStatus("get an overstay penalty", ReservationStatus.IN_PROGRESS);
        this.overstayPenalty = penalty;
    }

    public void checkOut(Instant now) {
        requireStatus("be checked out", ReservationStatus.IN_PROGRESS);
        this.status = ReservationStatus.COMPLETED;
        this.checkOutAt = now;
        this.finalCost = overstayPenalty == null ? spotCost : spotCost.add(overstayPenalty);
    }

    /** Cancelled by the driver, the owner or support, or by the payment timeout. Not possible after check-in. */
    public void cancel(String reason, Instant now) {
        requireStatus("be cancelled", ReservationStatus.PENDING_PAYMENT, ReservationStatus.PENDING_APPROVAL,
                ReservationStatus.CONFIRMED);
        this.status = ReservationStatus.CANCELLED;
        this.cancelReason = reason;
        this.cancelledAt = now;
    }

    /** True while the reservation keeps the time slot taken (same set as the database exclusion constraint). */
    public boolean holdsSlot() {
        return status.holdsSlot();
    }

    /** Whole minutes (rounded up) between the reserved end and the check-out; 0 when on time. */
    public long overstayMinutes() {
        if (checkOutAt == null || !checkOutAt.isAfter(period.end())) {
            return 0;
        }
        return (java.time.Duration.between(period.end(), checkOutAt).toSeconds() + 59) / 60;
    }

    /** True when the driver is still checked in after the reserved end. */
    public boolean isOverstaying(Instant now) {
        return status == ReservationStatus.IN_PROGRESS && now.isAfter(period.end());
    }

    private void confirm() {
        this.status = ReservationStatus.CONFIRMED;
        this.pinCode = String.format("%04d", RANDOM.nextInt(10_000));
        byte[] token = new byte[18];
        RANDOM.nextBytes(token);
        this.qrToken = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    private void requireStatus(String action, ReservationStatus... allowed) {
        for (ReservationStatus s : allowed) {
            if (status == s) {
                return;
            }
        }
        throw new IllegalStateTransitionException("Reservation", status, action);
    }
}
