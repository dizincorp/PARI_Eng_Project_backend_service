package pl.pollub.pari.support.service;

import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.payment.domain.Payment;
import pl.pollub.pari.payment.domain.PaymentStatus;
import pl.pollub.pari.payment.domain.PaymentType;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.support.domain.PenaltyAssessment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The automatic overstay penalty of a reservation and what happened to it (PARI-100). It is separate from the penalties
 * support assessed ({@link SupportCharges.PenaltyItem}).
 *
 * <p>{@code amount} is what was billed and never changes after a refund. {@code state}: NONE (no penalty), PENDING (held
 * because the driver said they could not leave), CHARGED, FAILED (the provider refused the payment), WAIVED (support waived
 * the held penalty) or REFUNDED (it was charged, then support returned it). {@code refundedAmount} and {@code refundedAt}
 * are set only for REFUNDED; {@code netAmount} is what the driver finally pays for the overstay: the amount, minus the
 * refund, and 0 unless the penalty was collected.
 */
public record OverstayPenaltyView(BigDecimal amount, String currency, State state, BigDecimal refundedAmount,
                                  Instant refundedAt, BigDecimal netAmount) {

    public enum State { NONE, PENDING, CHARGED, FAILED, WAIVED, REFUNDED }

    static OverstayPenaltyView of(Reservation r) {
        Money amount = r.getOverstayPenalty() == null ? Money.ZERO : r.getOverstayPenalty();
        State state = stateOf(r);
        Money refunded = state == State.REFUNDED ? r.getOverstayRefundPayment().getAmount() : Money.ZERO;
        Money net = state == State.CHARGED ? amount : Money.ZERO;
        return new OverstayPenaltyView(amount.toDecimal(), amount.currency(), state,
                state == State.REFUNDED ? refunded.toDecimal() : null, r.getOverstayRefundedAt(), net.toDecimal());
    }

    static State stateOf(Reservation r) {
        if (r.isOverstayPenaltyRefunded()) {
            return State.REFUNDED;
        }
        Money penalty = r.getOverstayPenalty();
        if (penalty == null) {
            return State.NONE;
        }
        if (r.isPenaltyPending()) {
            return State.PENDING;
        }
        if (penalty.isZero()) {
            return State.WAIVED;
        }
        Optional<Payment> payment = chargedPayment(r);
        if (payment.isPresent()) {
            return payment.get().getStatus() == PaymentStatus.SUCCEEDED ? State.CHARGED : State.FAILED;
        }
        return State.NONE;
    }

    /**
     * The payment of the automatic overstay penalty: a PENALTY payment of the reservation that does not belong to a penalty
     * support assessed. A successful one wins; otherwise the latest attempt.
     */
    static Optional<Payment> chargedPayment(Reservation r) {
        Set<UUID> assessed = r.getSupportPenalties().stream().map(PenaltyAssessment::getPayment)
                .filter(p -> p != null).map(Payment::getId).collect(Collectors.toSet());
        var automatic = r.getPayments().stream()
                .filter(p -> p.getType() == PaymentType.PENALTY && !assessed.contains(p.getId())).toList();
        Optional<Payment> succeeded = automatic.stream().filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED).findFirst();
        if (succeeded.isPresent()) {
            return succeeded;
        }
        return automatic.isEmpty() ? Optional.empty() : Optional.of(automatic.get(automatic.size() - 1));
    }
}
