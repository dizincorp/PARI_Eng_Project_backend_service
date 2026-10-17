package pl.pollub.pari.payment.gateway;

import org.junit.jupiter.api.Test;
import pl.pollub.pari.common.domain.Money;

import static org.assertj.core.api.Assertions.assertThat;

class MockPaymentGatewayTest {

    private final MockPaymentGateway gateway = new MockPaymentGateway();

    @Test
    void anyOrdinaryTokenIsCharged() {
        PaymentGateway.Result result = gateway.charge(Money.pln(2100), "pm_card_visa", "reservation-1");

        assertThat(result.succeeded()).isTrue();
        assertThat(result.providerRef()).startsWith("mock_pi_");
    }

    @Test
    void sameIdempotencyKeyGivesSameReference() {
        String first = gateway.charge(Money.pln(2100), "pm_card_visa", "reservation-1").providerRef();
        String second = gateway.charge(Money.pln(2100), "pm_card_visa", "reservation-1").providerRef();
        String other = gateway.charge(Money.pln(2100), "pm_card_visa", "reservation-2").providerRef();

        assertThat(second).isEqualTo(first);
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    void testDeclineTokensAreRefusedWithAReason() {
        PaymentGateway.Result declined = gateway.charge(Money.pln(2100), MockPaymentGateway.DECLINED_TOKEN, "k");
        PaymentGateway.Result noFunds = gateway.charge(Money.pln(2100), MockPaymentGateway.INSUFFICIENT_FUNDS_TOKEN, "k");

        assertThat(declined.succeeded()).isFalse();
        assertThat(declined.failureReason()).isEqualTo("The card was declined");
        assertThat(noFunds.failureReason()).isEqualTo("Insufficient funds");
    }

    @Test
    void missingTokenOrNonPositiveAmountIsRefused() {
        assertThat(gateway.charge(Money.pln(2100), " ", "k").succeeded()).isFalse();
        assertThat(gateway.charge(Money.pln(2100), null, "k").succeeded()).isFalse();
        assertThat(gateway.charge(Money.ZERO, "pm_card_visa", "k").succeeded()).isFalse();
    }

    @Test
    void refundNeedsAChargeMadeByTheMock() {
        String charge = gateway.charge(Money.pln(2100), "pm_card_visa", "k").providerRef();

        assertThat(gateway.refund(charge, Money.pln(2100)).succeeded()).isTrue();
        assertThat(gateway.refund("pi_real_stripe_id", Money.pln(2100)).succeeded()).isFalse();
        assertThat(gateway.refund(null, Money.pln(2100)).succeeded()).isFalse();
    }
}
