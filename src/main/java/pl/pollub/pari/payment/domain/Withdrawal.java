package pl.pollub.pari.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import pl.pollub.pari.common.domain.BaseEntity;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.security.EncryptedStringConverter;
import pl.pollub.pari.user.domain.User;

/** The owner takes money from the balance of released payouts to a bank account (F17). Only the mock gateway exists (ADR 0004). */
@Entity
@Table(name = "withdrawals")
@Getter
public class Withdrawal extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "amount_minor", nullable = false)
    private Money amount;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false)
    private String iban;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WithdrawalStatus status;

    private String providerRef;

    protected Withdrawal() {
    }

    public Withdrawal(User owner, Money amount, String iban, String providerRef) {
        this.owner = owner;
        this.amount = amount;
        this.iban = iban;
        this.providerRef = providerRef;
        this.status = WithdrawalStatus.COMPLETED;
    }

    /** Last four characters only, for responses and logs. */
    public String maskedIban() {
        return "****" + iban.substring(Math.max(0, iban.length() - 4));
    }
}
