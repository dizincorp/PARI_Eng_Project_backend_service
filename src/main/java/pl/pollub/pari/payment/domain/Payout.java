package pl.pollub.pari.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import pl.pollub.pari.common.domain.BaseEntity;
import pl.pollub.pari.common.domain.IllegalStateTransitionException;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.user.domain.User;

import java.time.Instant;

/** The owner's settlement for one reservation (soft escrow, F17): gross minus platform commission, paid after release_at. */
@Entity
@Table(name = "payouts")
@Getter
public class Payout extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false, unique = true)
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "gross_minor", nullable = false)
    private Money gross;

    @Column(name = "commission_minor", nullable = false)
    private Money commission;

    @Column(name = "net_minor", nullable = false)
    private Money net;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PayoutStatus status = PayoutStatus.SCHEDULED;

    @Column(nullable = false)
    private Instant releaseAt;

    private Instant paidAt;

    private String providerRef;

    protected Payout() {
    }

    public Payout(Reservation reservation, User owner, Money gross, Money commission, Instant releaseAt) {
        this.reservation = reservation;
        this.owner = owner;
        this.gross = gross;
        this.commission = commission;
        this.net = gross.subtract(commission);
        this.releaseAt = releaseAt;
    }

    public boolean isDue(Instant now) {
        return status == PayoutStatus.SCHEDULED && !now.isBefore(releaseAt);
    }

    /** An open report pauses the payout until support decides. */
    public void hold() {
        require("be held", PayoutStatus.SCHEDULED);
        this.status = PayoutStatus.HELD;
    }

    public void resume() {
        require("be resumed", PayoutStatus.HELD);
        this.status = PayoutStatus.SCHEDULED;
    }

    public void markPaid(Instant now, String providerRef) {
        require("be paid", PayoutStatus.SCHEDULED);
        this.status = PayoutStatus.PAID;
        this.paidAt = now;
        this.providerRef = providerRef;
    }

    /** The reservation was refunded: the owner gets nothing. */
    public void cancel() {
        require("be cancelled", PayoutStatus.SCHEDULED, PayoutStatus.HELD);
        this.status = PayoutStatus.CANCELLED;
    }

    private void require(String action, PayoutStatus... allowed) {
        for (PayoutStatus s : allowed) {
            if (status == s) {
                return;
            }
        }
        throw new IllegalStateTransitionException("Payout", status, action);
    }
}
