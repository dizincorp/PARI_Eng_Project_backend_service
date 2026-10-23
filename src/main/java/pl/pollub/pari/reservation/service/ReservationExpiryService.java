package pl.pollub.pari.reservation.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.common.service.AuditTrail;
import pl.pollub.pari.common.service.PlatformSettings;
import pl.pollub.pari.payment.service.PaymentService;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.event.ReservationEvent;
import pl.pollub.pari.reservation.repository.ReservationRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Expires requests the owner did not answer (PARI-38): a PENDING_APPROVAL reservation is older than
 * {@code approval_timeout_minutes} or its start has passed. The driver gets the whole payment back.
 */
@Service
public class ReservationExpiryService {

    private final ReservationRepository reservations;
    private final PaymentService payments;
    private final PlatformSettings settings;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ReservationExpiryService(ReservationRepository reservations, PaymentService payments, PlatformSettings settings,
                                    AuditTrail audit, ApplicationEventPublisher events, Clock clock) {
        this.reservations = reservations;
        this.payments = payments;
        this.settings = settings;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /** Ids of requests that are due to expire now. */
    @Transactional(readOnly = true)
    public List<UUID> findDueIds() {
        Instant now = clock.instant();
        return reservations.findPendingApprovalDueForExpiry(now.minus(timeout()), now).stream().map(Reservation::getId).toList();
    }

    /**
     * Expires one request in its own transaction, so one failing refund does not block the others. Does nothing when
     * the owner answered in the meantime.
     *
     * @return true when the reservation was expired
     */
    @Transactional
    public boolean expire(UUID reservationId) {
        Instant now = clock.instant();
        Reservation reservation = reservations.findById(reservationId).orElse(null);
        if (reservation == null || reservation.getStatus() != ReservationStatus.PENDING_APPROVAL || !isDue(reservation, now)) {
            return false;
        }
        reservation.expire(now);
        payments.refundInFull(reservation);
        audit.record(null, "Reservation", reservation.getId(), "EXPIRED", Map.of("code", reservation.getCode()));
        events.publishEvent(ReservationEvent.of(ReservationEvent.Type.EXPIRED, reservation, null));
        return true;
    }

    private boolean isDue(Reservation r, Instant now) {
        return !r.getCreatedAt().isAfter(now.minus(timeout())) || !r.getPeriod().start().isAfter(now);
    }

    private Duration timeout() {
        return Duration.ofMinutes(settings.integer("approval_timeout_minutes"));
    }
}
