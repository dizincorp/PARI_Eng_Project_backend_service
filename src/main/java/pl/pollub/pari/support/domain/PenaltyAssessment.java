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

import java.time.Instant;

/**
 * A penalty a support employee assessed on the reservation of a ticket (PARI-87). It is separate from the automatic
 * overstay penalty on the reservation. Assessing only records a PENDING amount; collection happens when support charges it.
 */
@Entity
@Table(name = "support_penalties")
@Getter
public class PenaltyAssessment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Report ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_user_id", nullable = false)
    private User target;

    @Column(name = "amount_minor", nullable = false)
    private Money amount;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PenaltyState state = PenaltyState.PENDING;

    @Column(nullable = false)
    private String operationKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assessed_by", nullable = false)
    private User assessedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private User decidedBy;

    private Instant decidedAt;

    @Column(columnDefinition = "text")
    private String decisionReason;

    protected PenaltyAssessment() {
    }

    public PenaltyAssessment(Report ticket, User target, Money amount, String reason, String operationKey, User assessedBy) {
        if (amount == null || amount.amountMinor() <= 0) {
            throw new IllegalArgumentException("A penalty amount must be positive");
        }
        this.reservation = ticket.getReservation();
        this.ticket = ticket;
        this.target = target;
        this.amount = amount;
        this.reason = reason;
        this.operationKey = operationKey;
        this.assessedBy = assessedBy;
    }

    /** Support collected the penalty; the payment says whether the provider accepted it. */
    public void settleWith(Payment payment, User by, String why, Instant now) {
        requirePending("be charged");
        this.payment = payment;
        this.state = payment.getStatus() == PaymentStatus.SUCCEEDED ? PenaltyState.CHARGED : PenaltyState.FAILED;
        decide(by, why, now);
    }

    public void waive(User by, String why, Instant now) {
        requirePending("be waived");
        this.state = PenaltyState.WAIVED;
        decide(by, why, now);
    }

    private void decide(User by, String why, Instant now) {
        this.decidedBy = by;
        this.decisionReason = why;
        this.decidedAt = now;
    }

    private void requirePending(String action) {
        if (state != PenaltyState.PENDING) {
            throw new IllegalStateTransitionException("Penalty", state, action);
        }
    }
}
