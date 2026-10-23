package pl.pollub.pari.reservation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Runs {@link ReservationExpiryService} periodically (PARI-38). */
@Component
public class ReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryJob.class);

    private final ReservationExpiryService expiry;

    public ReservationExpiryJob(ReservationExpiryService expiry) {
        this.expiry = expiry;
    }

    @Scheduled(fixedDelayString = "${pari.reservation.expiry-check-interval:PT1M}")
    public void expireUnansweredRequests() {
        for (UUID id : expiry.findDueIds()) {
            try {
                expiry.expire(id);
            } catch (RuntimeException e) {
                log.warn("Could not expire reservation {}: {}", id, e.getMessage());
            }
        }
    }
}
