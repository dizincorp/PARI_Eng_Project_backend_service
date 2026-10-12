package pl.pollub.pari.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import pl.pollub.pari.common.domain.BaseEntity;

/** Reference to a card stored at the payment provider. PARI never stores card numbers (NF03). */
@Entity
@Table(name = "payment_methods")
@Getter
public class PaymentMethod extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String providerRef;

    @Column(nullable = false)
    private String brand;

    @Column(nullable = false, length = 4)
    private String last4;

    private int expMonth;

    private int expYear;

    @Column(name = "is_default", nullable = false)
    private boolean defaultMethod;

    protected PaymentMethod() {
    }

    public PaymentMethod(User user, String providerRef, String brand, String last4, int expMonth, int expYear) {
        this.user = user;
        this.providerRef = providerRef;
        this.brand = brand;
        this.last4 = last4;
        this.expMonth = expMonth;
        this.expYear = expYear;
    }

    public void markDefault(boolean value) {
        this.defaultMethod = value;
    }
}
