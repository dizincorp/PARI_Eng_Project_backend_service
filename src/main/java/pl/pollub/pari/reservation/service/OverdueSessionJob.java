package pl.pollub.pari.reservation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Runs {@link OverdueSessionService} every minute (PARI-47), so a session the driver forgot to end does not stay IN_PROGRESS. */
@Component
public class OverdueSessionJob {

    private static final Logger log = LoggerFactory.getLogger(OverdueSessionJob.class);

    private final OverdueSessionService overdue;

    public OverdueSessionJob(OverdueSessionService overdue) {
        this.overdue = overdue;
    }

    @Scheduled(fixedDelayString = "${pari.reservation.overdue-check-interval:PT1M}")
    public void endOverdueSessions() {
        Instant dueBefore = overdue.dueBefore();
        for (UUID id : overdue.findDueIds()) {
            try {
                overdue.endSession(id, dueBefore);
            } catch (RuntimeException e) {
                log.warn("Could not end overdue session {}: {}", id, e.getMessage());
            }
        }
    }
}
