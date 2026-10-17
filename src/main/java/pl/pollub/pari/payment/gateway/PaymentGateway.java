package pl.pollub.pari.payment.gateway;

import pl.pollub.pari.common.domain.Money;

/**
 * Port to the card payment provider (NF03). The only implementation today is {@link MockPaymentGateway}; a Stripe
 * adapter will implement the same interface, so nothing else in the code base changes when real payments arrive.
 */
public interface PaymentGateway {

    /**
     * Charges the payment method. {@code idempotencyKey} makes a retried call return the same result instead of charging twice.
     */
    Result charge(Money amount, String paymentMethodToken, String idempotencyKey);

    /** Returns money of an earlier charge to the driver. */
    Result refund(String chargeProviderRef, Money amount);

    /** Outcome of a provider call. {@code providerRef} is set on success, {@code failureReason} on failure. */
    record Result(boolean succeeded, String providerRef, String failureReason) {

        public static Result success(String providerRef) {
            return new Result(true, providerRef, null);
        }

        public static Result failure(String reason) {
            return new Result(false, null, reason);
        }
    }
}
