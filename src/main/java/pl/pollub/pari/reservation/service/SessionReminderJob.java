package pl.pollub.pari.reservation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Runs {@link SessionReminderService} every minute (PARI-73). */
@Component
public class SessionReminderJob {

    private static final Logger log = LoggerFactory.getLogger(SessionReminderJob.class);

    private final SessionReminderService reminders;

    public SessionReminderJob(SessionReminderService reminders) {
        this.reminders = reminders;
    }

    @Scheduled(fixedDelayString = "${pari.reservation.reminder-check-interval:PT1M}")
    public void sendReminders() {
        for (UUID id : reminders.findCandidateIds()) {
            try {
                reminders.sendDue(id);
            } catch (RuntimeException e) {
                log.warn("Could not send reminders for reservation {}: {}", id, e.getMessage());
            }
        }
    }
}
