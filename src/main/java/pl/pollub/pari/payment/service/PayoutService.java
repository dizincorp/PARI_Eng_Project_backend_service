package pl.pollub.pari.payment.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.common.service.AuditTrail;
import pl.pollub.pari.payment.domain.Payout;
import pl.pollub.pari.payment.domain.PayoutStatus;
import pl.pollub.pari.payment.repository.PayoutRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Releases payouts from soft escrow (F17): once release_at has passed they are PAID and count as the owner's available balance. */
@Service
public class PayoutService {

    private final PayoutRepository payouts;
    private final AuditTrail audit;
    private final Clock clock;

    public PayoutService(PayoutRepository payouts, AuditTrail audit, Clock clock) {
        this.payouts = payouts;
        this.audit = audit;
        this.clock = clock;
    }

    /** Marks every SCHEDULED payout whose release time has passed as PAID. HELD payouts wait for support. @return how many were released */
    @Transactional
    public int releaseDue() {
        Instant now = clock.instant();
        List<Payout> due = payouts.findByStatusAndReleaseAtLessThanEqual(PayoutStatus.SCHEDULED, now);
        for (Payout payout : due) {
            payout.markPaid(now, null);
            audit.record(null, "Payout", payout.getId(), "RELEASED",
                    Map.of("netMinor", payout.getNet().amountMinor(), "commissionMinor", payout.getCommission().amountMinor()));
        }
        return due.size();
    }
}
