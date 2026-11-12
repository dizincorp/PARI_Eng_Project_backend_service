package pl.pollub.pari.support.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pl.pollub.pari.common.service.PlatformSettings;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.repository.ReservationRepository;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Opens a SYSTEM ticket (VEHICLE_NOT_LEFT) for every session still running {@code auto_ticket_after_end_minutes}
 * (15) after the reserved end, unless one is already open (PARI-70).
 */
@Component
public class VehicleNotLeftJob {

    private static final Logger log = LoggerFactory.getLogger(VehicleNotLeftJob.class);

    private final ReservationRepository reservations;
    private final SupportTicketService tickets;
    private final PlatformSettings settings;
    private final Clock clock;

    public VehicleNotLeftJob(ReservationRepository reservations, SupportTicketService tickets, PlatformSettings settings, Clock clock) {
        this.reservations = reservations;
        this.tickets = tickets;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${pari.support.auto-ticket-check-interval:PT1M}")
    public void openTickets() {
        for (UUID id : findDueIds()) {
            try {
                tickets.createSystemTicket(id);
            } catch (RuntimeException e) {
                log.warn("Could not open a ticket for reservation {}: {}", id, e.getMessage());
            }
        }
    }

    List<UUID> findDueIds() {
        var dueEnd = clock.instant().minus(Duration.ofMinutes(settings.integer("auto_ticket_after_end_minutes")));
        return reservations.findRunningEndedBy(dueEnd).stream().map(Reservation::getId).toList();
    }
}
