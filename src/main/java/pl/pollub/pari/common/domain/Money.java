package pl.pollub.pari.common.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Amount of money in minor units (grosze). The platform currently works in one currency (PLN),
 * so the database stores only the minor units; {@link MoneyConverter} restores the currency.
 */
public record Money(long amountMinor, String currency) implements Comparable<Money> {

    public static final String PLN = "PLN";
    public static final Money ZERO = new Money(0, PLN);

    public Money {
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency is required");
        }
    }

    public static Money pln(long amountMinor) {
        return new Money(amountMinor, PLN);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amountMinor, other.amountMinor), currency);
    }

    /** Multiplies and rounds half up to a whole minor unit. */
    public Money multiply(BigDecimal factor) {
        BigDecimal result = BigDecimal.valueOf(amountMinor).multiply(factor).setScale(0, RoundingMode.HALF_UP);
        return new Money(result.longValueExact(), currency);
    }

    public boolean isNegative() {
        return amountMinor < 0;
    }

    public boolean isZero() {
        return amountMinor == 0;
    }

    /** Decimal amount for the API, for example 42.50. */
    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(amountMinor, 2);
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(amountMinor, other.amountMinor);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
