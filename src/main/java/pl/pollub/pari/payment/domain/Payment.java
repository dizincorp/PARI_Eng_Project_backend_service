package pl.pollub.pari.payment.domain;

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
import pl.pollub.pari.reservation.domain.Reservation;

/** A money movement between the driver and the platform: charge, overstay penalty or refund. */
@Entity
@Table(name = "payments")
@Getter
public class Payment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentType type;

    /** Provider reference (Stripe pi_ / re_). Unique, so a repeated webhook cannot create a second row. */
    private String providerRef;

    @Column(name = "amount_minor", nullable = false)
    private Money amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status = PaymentStatus.PENDING;

    protected Payment() {
    }

    public Payment(Reservation reservation, PaymentType type, Money amount, String providerRef) {
        this.reservation = reservation;
        this.type = type;
        this.amount = amount;
        this.providerRef = providerRef;
    }

    public void markSucceeded() {
        requirePending("succeed");
        this.status = PaymentStatus.SUCCEEDED;
    }

    public void markFailed() {
        requirePending("fail");
        this.status = PaymentStatus.FAILED;
    }

    private void requirePending(String action) {
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateTransitionException("Payment", status, action);
        }
    }
}
