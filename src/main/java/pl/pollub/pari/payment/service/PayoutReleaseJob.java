package pl.pollub.pari.payment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs {@link PayoutService#releaseDue()} regularly. The interval is technical, not a business value. */
@Component
@EnableScheduling
public class PayoutReleaseJob {

    private static final Logger log = LoggerFactory.getLogger(PayoutReleaseJob.class);

    private final PayoutService payoutService;

    public PayoutReleaseJob(PayoutService payoutService) {
        this.payoutService = payoutService;
    }

    @Scheduled(fixedDelayString = "${pari.payout.release-interval:PT10M}", initialDelayString = "${pari.payout.release-interval:PT10M}")
    public void run() {
        int released = payoutService.releaseDue();
        if (released > 0) {
            log.info("Released {} payouts to owner balances", released);
        }
    }
}
