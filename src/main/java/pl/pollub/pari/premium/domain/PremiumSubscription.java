package pl.pollub.pari.premium.domain;

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
import pl.pollub.pari.user.domain.User;

import java.time.Instant;

/** An owner's Premium plan (F08): priority in search results and extended statistics. */
@Entity
@Table(name = "premium_subscriptions")
@Getter
public class PremiumSubscription extends BaseEntity {

    public static final String MONTHLY = "PREMIUM_MONTHLY";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false)
    private String plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

    @Column(nullable = false)
    private Instant startedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    private String providerRef;

    protected PremiumSubscription() {
    }

    public PremiumSubscription(User owner, String plan, Instant startedAt, Instant expiresAt, String providerRef) {
        this.owner = owner;
        this.plan = plan;
        this.startedAt = startedAt;
        this.expiresAt = expiresAt;
        this.providerRef = providerRef;
    }

    /** Premium benefits apply until the paid period ends, also after the owner cancelled (cancelling only stops renewal). */
    public boolean isActive(Instant now) {
        return status != SubscriptionStatus.EXPIRED && now.isBefore(expiresAt);
    }

    /** The owner cancels; the plan stays usable until expiresAt. */
    public void cancel() {
        if (status == SubscriptionStatus.ACTIVE) {
            this.status = SubscriptionStatus.CANCELLED;
        }
    }

    public void expire() {
        this.status = SubscriptionStatus.EXPIRED;
    }

    public void renew(Instant newExpiry) {
        this.status = SubscriptionStatus.ACTIVE;
        this.expiresAt = newExpiry;
    }
}
