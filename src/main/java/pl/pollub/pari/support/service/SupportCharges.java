package pl.pollub.pari.support.service;

import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.support.domain.EvacuationFee;
import pl.pollub.pari.support.domain.PenaltyState;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The money support put on a reservation, as the API shows it (PARI-87, PARI-88): itemized records and the authoritative
 * totals. Both are kept apart from the parking price ({@code spotCost}) and from the automatic overstay penalty.
 */
public final class SupportCharges {

    private SupportCharges() {
    }

    /** A penalty support assessed. {@code state} is PENDING, CHARGED, WAIVED or FAILED; amounts are PLN decimals. */
    public record PenaltyItem(UUID id, UUID ticketId, UUID targetUserId, BigDecimal amount, String currency, String reason,
                              PenaltyState state, Instant createdAt, Instant decidedAt, String decisionReason) {
    }

    /** One evacuation charge ({@code kind} FEE) or the full reversal of one ({@code kind} REFUND, {@code refundsFeeId} set). */
    public record FeeItem(UUID id, UUID ticketId, EvacuationFee.Kind kind, EvacuationFee.Status status, BigDecimal amount,
                          String currency, String reason, UUID refundsFeeId, Instant createdAt) {
    }

    public static List<PenaltyItem> penalties(Reservation r) {
        return r.getSupportPenalties().stream().map(p -> new PenaltyItem(p.getId(), p.getTicket().getId(), p.getTarget().getId(),
                p.getAmount().toDecimal(), p.getAmount().currency(), p.getReason(), p.getState(), p.getCreatedAt(),
                p.getDecidedAt(), p.getDecisionReason())).toList();
    }

    /** Sum of the support penalties that were actually collected. Pending, waived and failed ones do not count. */
    public static BigDecimal penaltyTotal(Reservation r) {
        long minor = r.getSupportPenalties().stream().filter(p -> p.getState() == PenaltyState.CHARGED)
                .mapToLong(p -> p.getAmount().amountMinor()).sum();
        return Money.pln(minor).toDecimal();
    }

    public static List<FeeItem> fees(Reservation r) {
        return r.getEvacuationFees().stream().map(f -> new FeeItem(f.getId(), f.getTicket().getId(), f.getKind(), f.getStatus(),
                f.getAmount().toDecimal(), f.getAmount().currency(), f.getReason(),
                f.getRefunds() == null ? null : f.getRefunds().getId(), f.getCreatedAt())).toList();
    }

    /** Charged fees minus refunds; failed fees are not part of it. */
    public static BigDecimal evacuationFeeTotal(Reservation r) {
        long minor = r.getEvacuationFees().stream().mapToLong(f -> {
            if (f.isEligibleFee()) {
                return f.getAmount().amountMinor();
            }
            return f.getKind() == EvacuationFee.Kind.REFUND ? -f.getAmount().amountMinor() : 0;
        }).sum();
        return Money.pln(minor).toDecimal();
    }
}
