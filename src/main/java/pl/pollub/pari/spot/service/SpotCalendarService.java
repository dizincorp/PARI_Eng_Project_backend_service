package pl.pollub.pari.spot.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.availability.domain.ExceptionKind;
import pl.pollub.pari.availability.repository.AvailabilityExceptionRepository;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.repository.ReservationRepository;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.spot.domain.SpotStatus;
import pl.pollub.pari.spot.repository.ParkingSpotRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** The occupied time of a space, so a driver can pick a free slot and an owner can see the schedule (PARI-20). */
@Service
public class SpotCalendarService {

    /** A reservation that is not cancelled or rejected. */
    private static final Set<ReservationStatus> OCCUPYING = EnumSet.complementOf(
            EnumSet.of(ReservationStatus.CANCELLED, ReservationStatus.REJECTED));

    /**
     * One occupied interval. {@code reservationId} is filled only for the owner of the space and the driver of that
     * reservation; others see the interval without knowing whose it is. A blocked period has {@code status} BLOCKED and a {@code blockId}.
     */
    public record BookedSlot(UUID reservationId, UUID blockId, Instant start, Instant end, String status) {
    }

    private final ParkingSpotRepository spots;
    private final ReservationRepository reservations;
    private final AvailabilityExceptionRepository exceptions;

    public SpotCalendarService(ParkingSpotRepository spots, ReservationRepository reservations,
                               AvailabilityExceptionRepository exceptions) {
        this.spots = spots;
        this.reservations = reservations;
        this.exceptions = exceptions;
    }

    /** Intervals with {@code start < to} and {@code end > from}: live reservations and the owner's blocked periods, earliest first. */
    @Transactional(readOnly = true)
    public List<BookedSlot> bookedSlots(UUID viewerId, UUID spotId, Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw new IllegalArgumentException("'to' must be after 'from'");
        }
        ParkingSpot spot = spots.findById(spotId).orElseThrow(() -> new NotFoundException("Parking spot not found"));
        boolean isOwner = spot.getOwner().getId().equals(viewerId);
        if ((spot.getStatus() != SpotStatus.ACTIVE && !isOwner) || spot.getStatus() == SpotStatus.ARCHIVED) {
            throw new NotFoundException("Parking spot not found");
        }
        List<BookedSlot> slots = new ArrayList<>();
        for (Reservation r : reservations.findOverlapping(spotId, OCCUPYING, from, to)) {
            boolean mayIdentify = isOwner || r.getDriver().getId().equals(viewerId);
            slots.add(new BookedSlot(mayIdentify ? r.getId() : null, null, r.getPeriod().start(), r.getPeriod().end(),
                    r.getStatus().name()));
        }
        exceptions.findOverlapping(spotId, from, to).stream()
                .filter(e -> e.getKind() == ExceptionKind.BLOCKED)
                .forEach(e -> slots.add(new BookedSlot(null, isOwner ? e.getId() : null, e.getPeriod().start(),
                        e.getPeriod().end(), "BLOCKED")));
        slots.sort(Comparator.comparing(BookedSlot::start));
        return slots;
    }
}
