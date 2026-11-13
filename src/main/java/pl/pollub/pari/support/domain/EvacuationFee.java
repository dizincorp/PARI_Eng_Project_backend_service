package pl.pollub.pari.support.domain;

import jakarta.persistence.Column;
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
import pl.pollub.pari.payment.domain.Payment;
import pl.pollub.pari.payment.domain.PaymentStatus;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.user.domain.User;

/**
 * One item of the evacuation charges of a reservation (PARI-88). Items are never edited: a FEE is a charge, a REFUND
 * reverses exactly one FEE in full. The reservation's evacuation total is the sum of its CHARGED fees minus its refunds.
 */
@Entity
@Table(name = "evacuation_fees")
@Getter
public class EvacuationFee extends BaseEntity {

    public enum Kind { FEE, REFUND }

    public enum Status {
        /** A FEE the provider accepted. */
        CHARGED,
        /** A FEE the provider refused; it never counts towards the total. */
        FAILED,
        /** A REFUND that was paid out. */
        REFUNDED
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Report ticket;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(name = "amount_minor", nullable = false)
    private Money amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @Column(nullable = false)
    private String operationKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refunds_fee_id")
    private EvacuationFee refunds;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    protected EvacuationFee() {
    }

    private EvacuationFee(Report ticket, Kind kind, Money amount, Status status, String reason, String operationKey,
                          Payment payment, EvacuationFee refunds, User by) {
        if (amount == null || amount.amountMinor() <= 0) {
            throw new IllegalArgumentException("An evacuation fee must be positive");
        }
        this.reservation = ticket.getReservation();
        this.ticket = ticket;
        this.kind = kind;
        this.amount = amount;
        this.status = status;
        this.reason = reason;
        this.operationKey = operationKey;
        this.payment = payment;
        this.refunds = refunds;
        this.createdBy = by;
    }

    /** A charge for an evacuation; CHARGED when the payment succeeded, FAILED otherwise. */
    public static EvacuationFee charged(Report ticket, Money amount, Payment payment, String reason, String operationKey, User by) {
        Status status = payment.getStatus() == PaymentStatus.SUCCEEDED ? Status.CHARGED : Status.FAILED;
        return new EvacuationFee(ticket, Kind.FEE, amount, status, reason, operationKey, payment, null, by);
    }

    /** The full reversal of a CHARGED fee. */
    public static EvacuationFee refundOf(EvacuationFee fee, Payment refund, String reason, String operationKey, User by) {
        if (!fee.isEligibleFee()) {
            throw new IllegalStateTransitionException("Only a charged evacuation fee can be refunded");
        }
        return new EvacuationFee(fee.ticket, Kind.REFUND, fee.amount, Status.REFUNDED, reason, operationKey, refund, fee, by);
    }

    /** True for a FEE that was charged successfully, the only kind that counts towards the total. */
    public boolean isEligibleFee() {
        return kind == Kind.FEE && status == Status.CHARGED;
    }
}
