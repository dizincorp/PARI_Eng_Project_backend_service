package pl.pollub.pari.payment.service;

/** The payment provider refused a charge or a refund. The web layer maps it to HTTP 402. */
public class PaymentFailedException extends RuntimeException {

    public PaymentFailedException(String message) {
        super(message);
    }
}
