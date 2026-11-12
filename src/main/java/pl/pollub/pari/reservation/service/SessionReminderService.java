package pl.pollub.pari.reservation.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.common.service.PlatformSettings;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.event.ReservationEvent;
import pl.pollub.pari.reservation.repository.ReservationRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reminders to the driver of a running session (PARI-73): shortly before the reserved end, at the end, and a final one
 * after it (defaults 15 minutes before, 0, 45 minutes after). Each reminder is sent once per reservation; the
 * {@code remindersSent} bit mask on the reservation remembers which were sent.
 */
@Service
public class SessionReminderService {

    private final ReservationRepository reservations;
    private final PlatformSettings settings;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public SessionReminderService(ReservationRepository reservations, PlatformSettings settings, Clock clock,
                                  ApplicationEventPublisher events) {
        this.reservations = reservations;
        this.settings = settings;
        this.clock = clock;
        this.events = events;
    }

    /** Running sessions that may need a reminder now: their reminders can start from {@code reminder_before_end_minutes} before the end. */
    @Transactional(readOnly = true)
    public List<UUID> findCandidateIds() {
        Instant horizon = clock.instant().plus(Duration.ofMinutes(settings.integer("reminder_before_end_minutes")));
        return reservations.findRunningEndedBy(horizon).stream()
                .filter(r -> r.getRemindersSent() != (Reservation.REMINDER_BEFORE_END | Reservation.REMINDER_AT_END | Reservation.REMINDER_FINAL))
                .map(Reservation::getId).toList();
    }

    /** Sends the reminders that are due for one reservation; returns how many were sent. */
    @Transactional
    public int sendDue(UUID reservationId) {
        Reservation r = reservations.findById(reservationId).orElse(null);
        if (r == null || r.getStatus() != pl.pollub.pari.reservation.domain.ReservationStatus.IN_PROGRESS) {
            return 0;
        }
        Instant now = clock.instant();
        Instant end = r.getPeriod().end();
        int sent = 0;
        if (!now.isBefore(end.minus(Duration.ofMinutes(settings.integer("reminder_before_end_minutes"))))
                && r.markReminderSent(Reservation.REMINDER_BEFORE_END)) {
            if (now.isBefore(end)) { // a reminder "before the end" that is already late is skipped, the next one covers it
                events.publishEvent(ReservationEvent.of(ReservationEvent.Type.REMINDER_BEFORE_END, r, null));
                sent++;
            }
        }
        if (!now.isBefore(end) && r.markReminderSent(Reservation.REMINDER_AT_END)) {
            events.publishEvent(ReservationEvent.of(ReservationEvent.Type.REMINDER_AT_END, r, null));
            sent++;
        }
        if (!now.isBefore(end.plus(Duration.ofMinutes(settings.integer("reminder_final_after_end_minutes"))))
                && r.markReminderSent(Reservation.REMINDER_FINAL)) {
            events.publishEvent(ReservationEvent.of(ReservationEvent.Type.REMINDER_FINAL, r, null));
            sent++;
        }
        return sent;
    }
}
