package pl.pollub.pari.payment.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import pl.pollub.pari.common.domain.Money;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/**
 * Fake payment provider for development and tests: no network call, no real money. Active unless
 * {@code pari.payment.provider} is set to something else.
 *
 * <p>Behaves like Stripe's test tokens: {@code pm_card_visa} (or any other token) succeeds, the tokens in
 * {@link #DECLINED_TOKENS} are declined. Provider references are derived from the idempotency key, so a retried call
 * returns the same reference, as a real provider would.
 */
@Component
@ConditionalOnProperty(name = "pari.payment.provider", havingValue = "mock", matchIfMissing = true)
public class MockPaymentGateway implements PaymentGateway {

    public static final String DECLINED_TOKEN = "pm_card_chargeDeclined";
    public static final String INSUFFICIENT_FUNDS_TOKEN = "pm_card_chargeDeclinedInsufficientFunds";

    private static final Set<String> DECLINED_TOKENS = Set.of(DECLINED_TOKEN, INSUFFICIENT_FUNDS_TOKEN);

    @Override
    public Result charge(Money amount, String paymentMethodToken, String idempotencyKey) {
        if (paymentMethodToken == null || paymentMethodToken.isBlank()) {
            return Result.failure("A payment method is required");
        }
        if (amount.amountMinor() <= 0) {
            return Result.failure("Amount must be positive");
        }
        if (INSUFFICIENT_FUNDS_TOKEN.equals(paymentMethodToken)) {
            return Result.failure("Insufficient funds");
        }
        if (DECLINED_TOKENS.contains(paymentMethodToken)) {
            return Result.failure("The card was declined");
        }
        return Result.success("mock_pi_" + stableId(idempotencyKey));
    }

    @Override
    public Result refund(String chargeProviderRef, Money amount) {
        if (chargeProviderRef == null || !chargeProviderRef.startsWith("mock_pi_")) {
            return Result.failure("Unknown charge " + chargeProviderRef);
        }
        return Result.success("mock_re_" + stableId(chargeProviderRef + ":" + amount.amountMinor()));
    }

    @Override
    public Result payout(Money amount, String iban, String idempotencyKey) {
        if (iban == null || iban.isBlank()) {
            return Result.failure("A bank account is required");
        }
        if (amount.amountMinor() <= 0) {
            return Result.failure("Amount must be positive");
        }
        return Result.success("mock_po_" + stableId(idempotencyKey));
    }

    private static UUID stableId(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
