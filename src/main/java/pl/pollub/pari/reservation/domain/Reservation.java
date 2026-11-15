package pl.pollub.pari.reservation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.Hibernate;
import pl.pollub.pari.common.domain.BaseEntity;
import pl.pollub.pari.common.domain.IllegalStateTransitionException;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.domain.TimeRange;
import pl.pollub.pari.common.security.EncryptedStringConverter;
import pl.pollub.pari.payment.domain.Payment;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.spot.domain.SpotUnit;
import pl.pollub.pari.support.domain.EvacuationFee;
import pl.pollub.pari.support.domain.PenaltyAssessment;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * A booking of a parking space for a time range. Prices are copied at booking time, so later changes of the spot price
 * do not change an existing reservation. All status changes go through the methods below; any other transition
 * throws {@link IllegalStateTransitionException}.
 */
@Entity
@Table(name = "reservations")
@Getter
public class Reservation extends BaseEntity {

    public static final int REMINDER_BEFORE_END = 1;
    public static final int REMINDER_AT_END = 2;
    public static final int REMINDER_FINAL = 4;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Human readable id, for example RSV-20261012-8F3K2. */
    @Column(nullable = false, updatable = false)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "spot_id", nullable = false)
    private ParkingSpot spot;

    /** The space of the listing that is held (PARI-65); the exclusion constraint is keyed on it. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unit_id", nullable = false)
    private SpotUnit unit;

    /** The label of the space when the reservation was made, so a later rename does not change receipts. */
    @Column(nullable = false, length = SpotUnit.MAX_LABEL_LENGTH)
    private String unitLabel;

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

    /**
     * The space's owner-configured gate PIN as it was when the booking was confirmed (PARI-66), encrypted at rest. Never
     * changed afterwards; null when the owner had none, and for reservations confirmed before the snapshots existed.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "owner_gate_pin_snapshot", columnDefinition = "text")
    private String ownerGatePinSnapshot;

    /** The space's access instructions as they were when the booking was confirmed; same rules as the gate PIN snapshot. */
    @Column(name = "access_instructions_snapshot", columnDefinition = "text")
    private String accessInstructionsSnapshot;

    private Instant checkInAt;

    private Instant checkOutAt;

    /** Extra fee for staying longer than reserved (F19). */
    @Column(name = "overstay_penalty_minor")
    private Money overstayPenalty;

    @Column(name = "final_cost_minor")
    private Money finalCost;

    /** The spot stays taken until this moment: the reserved end plus the unpaid buffer (PARI-69). */
    @Column(name = "blocked_until", nullable = false)
    private Instant blockedUntil;

    /** True when the system, not the driver, ended the session (PARI-67). The status is still COMPLETED. */
    private boolean autoCheckedOut;

    /** The overstay penalty is set but not charged yet, because a driver ticket is open (PARI-71). */
    private boolean penaltyPending;

    /** Itemized evacuation charges and refunds of this reservation (PARI-88); written only through the support module. */
    @OneToMany(mappedBy = "reservation")
    @OrderBy("createdAt ASC")
    private List<EvacuationFee> evacuationFees = new ArrayList<>();

    /** Penalties support assessed on this reservation (PARI-87); the automatic overstay penalty is not in this list. */
    @OneToMany(mappedBy = "reservation")
    @OrderBy("createdAt ASC")
    private List<PenaltyAssessment> supportPenalties = new ArrayList<>();

    /** The money movements of this reservation, oldest first. Read only: payments are written through the payment module. */
    @OneToMany(mappedBy = "reservation")
    @OrderBy("createdAt ASC")
    private List<Payment> payments = new ArrayList<>();

    /** The REFUND that returned the automatic overstay penalty to the driver (PARI-100); null while it was not refunded. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "overstay_refund_payment_id")
    private Payment overstayRefundPayment;

    private Instant overstayRefundedAt;

    /** Idempotency key of the refund command: the same key again changes nothing. */
    private String overstayRefundOperationKey;

    /** Bit mask of the session reminders already sent, see {@link #REMINDER_BEFORE_END}. */
    private int remindersSent;

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
        this.blockedUntil = period.end();
        this.status = ReservationStatus.PENDING_PAYMENT;
        assignUnit(spot.firstLiveUnit());
    }

    /** Chooses the space of the listing this reservation holds; it defaults to the first live space. */
    public void assignUnit(SpotUnit unit) {
        this.unit = unit;
        this.unitLabel = unit == null ? null : unit.getLabel();
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

    /** Blocks the spot for {@code buffer} after the reserved end (PARI-69). Call when booking. */
    public void applyBuffer(java.time.Duration buffer) {
        this.blockedUntil = period.end().plus(buffer);
    }

    /** Keeps the spot blocked longer, for example while a VEHICLE_NOT_LEFT ticket is open. Never shortens the block. */
    public void extendBlockUntil(Instant until) {
        if (until.isAfter(blockedUntil)) {
            this.blockedUntil = until;
        }
    }

    /** Never checked in by the reserved end: no refund, the owner is paid, the slot is free (PARI-67). */
    public void markNoShow(Instant now) {
        requireStatus("become a no-show", ReservationStatus.CONFIRMED);
        if (now.isBefore(period.end())) {
            throw new IllegalStateTransitionException("a reservation is a no-show only after " + period.end());
        }
        this.status = ReservationStatus.NO_SHOW;
    }

    /** The system ended the session (PARI-67). Call right after {@link #checkOut(Instant)}. */
    public void markAutoCheckedOut() {
        requireStatus("be marked as auto checked out", ReservationStatus.COMPLETED);
        this.autoCheckedOut = true;
    }

    /**
     * Keeps the already loaded support lists in step with a record that was just saved in the same transaction. Lists that
     * were not loaded yet are left alone: they read the saved record from the database when they are first used.
     */
    public void registerEvacuationFee(EvacuationFee fee) {
        if (Hibernate.isInitialized(evacuationFees)) {
            evacuationFees.add(fee);
        }
    }

    public void registerSupportPenalty(PenaltyAssessment penalty) {
        if (Hibernate.isInitialized(supportPenalties)) {
            supportPenalties.add(penalty);
        }
    }

    /** The penalty is set but not charged yet (a driver ticket is open). */
    public void holdPenalty() {
        this.penaltyPending = true;
    }

    /** Support decided about a held penalty (PARI-71): {@code finalCost} drops the penalty when it is waived. */
    public void settlePenalty(boolean waive) {
        if (!penaltyPending) {
            throw new IllegalStateTransitionException("There is no pending penalty on this reservation");
        }
        this.penaltyPending = false;
        if (waive) {
            this.overstayPenalty = Money.ZERO;
            this.finalCost = spotCost;
        }
    }

    /**
     * Remembers that the charged overstay penalty was refunded (PARI-100). The billed amounts ({@code overstayPenalty},
     * {@code finalCost}) do not change: they say what was billed, the refund is recorded next to them. Only once.
     */
    public void recordOverstayRefund(Payment refund, String operationKey, Instant now) {
        if (overstayRefundPayment != null) {
            throw new IllegalStateTransitionException("The overstay penalty of this reservation was already refunded");
        }
        this.overstayRefundPayment = refund;
        this.overstayRefundedAt = now;
        this.overstayRefundOperationKey = operationKey;
    }

    /** True when the automatic overstay penalty was refunded by support. */
    public boolean isOverstayPenaltyRefunded() {
        return overstayRefundPayment != null;
    }

    /** Support corrects a wrongly assigned no-show: the reservation is cancelled (the service refunds the driver). */
    public void correctNoShow(String reason, Instant now) {
        requireStatus("be corrected", ReservationStatus.NO_SHOW);
        this.status = ReservationStatus.CANCELLED;
        this.cancelReason = reason;
        this.cancelledAt = now;
    }

    /** Marks a session reminder as sent; returns false when it was already sent. */
    public boolean markReminderSent(int reminder) {
        if ((remindersSent & reminder) != 0) {
            return false;
        }
        this.remindersSent |= reminder;
        return true;
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
        this.ownerGatePinSnapshot = spot.getOwnerGatePin();
        this.accessInstructionsSnapshot = spot.getAccessInstructions();
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
