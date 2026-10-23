package pl.pollub.pari.premium.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.ForbiddenException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.common.service.AuditTrail;
import pl.pollub.pari.common.service.PlatformSettings;
import pl.pollub.pari.payment.gateway.PaymentGateway;
import pl.pollub.pari.payment.service.PaymentFailedException;
import pl.pollub.pari.premium.domain.PremiumPlan;
import pl.pollub.pari.premium.domain.PremiumSubscription;
import pl.pollub.pari.premium.repository.PremiumSubscriptionRepository;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.repository.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owner Premium (F08): 0% commission and priority in search. Charged through the payment gateway (mock only, ADR 0004).
 * The plan prices are PLACEHOLDERS in platform_settings.
 */
@Service
public class PremiumService {

    /** {@code subscription} is empty when the owner has no active plan. */
    public record Status(Optional<PremiumSubscription> subscription) {

        public boolean isPremium() {
            return subscription.isPresent();
        }
    }

    private final PremiumSubscriptionRepository subscriptions;
    private final UserRepository users;
    private final PaymentGateway gateway;
    private final PlatformSettings settings;
    private final AuditTrail audit;
    private final Clock clock;

    public PremiumService(PremiumSubscriptionRepository subscriptions, UserRepository users, PaymentGateway gateway,
                          PlatformSettings settings, AuditTrail audit, Clock clock) {
        this.subscriptions = subscriptions;
        this.users = users;
        this.gateway = gateway;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    /** Buys a plan. Throws {@link PaymentFailedException} when the charge is declined (nothing is saved). */
    @Transactional
    public PremiumSubscription subscribe(UUID ownerId, PremiumPlan plan, String paymentMethodToken) {
        Instant now = clock.instant();
        User owner = users.findById(ownerId).orElseThrow(() -> new NotFoundException("User not found"));
        if (!owner.hasRole(Role.OWNER)) {
            throw new ForbiddenException("The OWNER role is required for a Premium subscription");
        }
        if (subscriptions.findCurrentOne(ownerId, now).isPresent()) {
            throw new ConflictException("ALREADY_PREMIUM", "The owner already has an active Premium subscription");
        }
        Money price = Money.pln(Long.parseLong(settings.decimal(plan.priceSettingKey()).toPlainString()));
        PaymentGateway.Result result = gateway.charge(price, paymentMethodToken,
                "premium-" + ownerId + "-" + plan + "-" + now.toEpochMilli());
        if (!result.succeeded()) {
            throw new PaymentFailedException(result.failureReason());
        }
        PremiumSubscription subscription = subscriptions.save(
                new PremiumSubscription(owner, plan.code(), now, plan.expiryFrom(now), result.providerRef()));
        audit.record(ownerId, "PremiumSubscription", subscription.getId(), "SUBSCRIBED",
                Map.of("plan", plan.name(), "priceMinor", price.amountMinor()));
        return subscription;
    }

    @Transactional(readOnly = true)
    public Status status(UUID ownerId) {
        return new Status(subscriptions.findCurrentOne(ownerId, clock.instant()));
    }

    /** True when the owner's Premium is active now (commission waiver, F18). */
    @Transactional(readOnly = true)
    public boolean isPremium(UUID ownerId) {
        return subscriptions.findCurrentOne(ownerId, clock.instant()).isPresent();
    }
}
