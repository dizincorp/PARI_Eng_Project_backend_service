package pl.pollub.pari.premium.domain;

import java.time.Instant;
import java.time.ZoneOffset;

/** Billing period of an owner's Premium plan. {@link #code()} is the value stored in {@code premium_subscriptions.plan}. */
public enum PremiumPlan {
    MONTHLY("premium_price_monthly_minor"),
    YEARLY("premium_price_yearly_minor");

    private final String priceSettingKey;

    PremiumPlan(String priceSettingKey) {
        this.priceSettingKey = priceSettingKey;
    }

    /** Key in platform_settings that holds the price in minor units. */
    public String priceSettingKey() {
        return priceSettingKey;
    }

    public String code() {
        return "PREMIUM_" + name();
    }

    public Instant expiryFrom(Instant start) {
        var utc = start.atOffset(ZoneOffset.UTC);
        return (this == MONTHLY ? utc.plusMonths(1) : utc.plusYears(1)).toInstant();
    }

    public static PremiumPlan fromCode(String code) {
        return valueOf(code.substring("PREMIUM_".length()));
    }
}
