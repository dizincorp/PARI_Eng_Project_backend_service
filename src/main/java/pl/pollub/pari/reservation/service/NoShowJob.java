package pl.pollub.pari.reservation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.repository.ReservationRepository;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** Moves CONFIRMED reservations whose reserved end has passed to NO_SHOW (PARI-67). */
@Component
public class NoShowJob {

    private static final Logger log = LoggerFactory.getLogger(NoShowJob.class);

    private final ReservationRepository reservations;
    private final ReservationService reservationService;
    private final Clock clock;

    public NoShowJob(ReservationRepository reservations, ReservationService reservationService, Clock clock) {
        this.reservations = reservations;
        this.reservationService = reservationService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${pari.reservation.no-show-check-interval:PT1M}")
    public void markNoShows() {
        for (UUID id : findDueIds()) {
            try {
                reservationService.markNoShow(id);
            } catch (RuntimeException e) {
                log.warn("Could not mark reservation {} as no-show: {}", id, e.getMessage());
            }
        }
    }

    List<UUID> findDueIds() {
        return reservations.findByStatusAndPeriodEndLessThanEqual(ReservationStatus.CONFIRMED, clock.instant()).stream()
                .map(Reservation::getId).toList();
    }
}
