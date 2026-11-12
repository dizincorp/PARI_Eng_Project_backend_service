package pl.pollub.pari.reservation.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.common.service.PlatformSettings;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.repository.ReservationRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Finds parking sessions that should have ended (PARI-47): IN_PROGRESS reservations whose reserved end plus
 * {@code auto_checkout_after_end_minutes} (60, independent of the penalty grace) has passed. {@link ReservationService#autoCheckOut} ends them.
 */
@Service
public class OverdueSessionService {

    private final ReservationRepository reservations;
    private final ReservationService reservationService;
    private final PlatformSettings settings;
    private final Clock clock;

    public OverdueSessionService(ReservationRepository reservations, ReservationService reservationService,
                                 PlatformSettings settings, Clock clock) {
        this.reservations = reservations;
        this.reservationService = reservationService;
        this.settings = settings;
        this.clock = clock;
    }

    /** Point in time before which a reserved end counts as overdue. */
    public Instant dueBefore() {
        return clock.instant().minus(Duration.ofMinutes(settings.integer("auto_checkout_after_end_minutes")));
    }

    @Transactional(readOnly = true)
    public List<UUID> findDueIds() {
        return reservations.findByStatusAndPeriodEndBefore(ReservationStatus.IN_PROGRESS, dueBefore()).stream()
                .map(Reservation::getId).toList();
    }

    /** Ends one overdue session in its own transaction. */
    public boolean endSession(UUID reservationId, Instant dueBefore) {
        return reservationService.autoCheckOut(reservationId, dueBefore);
    }
}
