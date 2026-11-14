package pl.pollub.pari.availability.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.availability.domain.AvailabilityException;
import pl.pollub.pari.availability.domain.AvailabilityRule;
import pl.pollub.pari.availability.domain.ExceptionKind;
import pl.pollub.pari.availability.repository.AvailabilityExceptionRepository;
import pl.pollub.pari.availability.repository.AvailabilityRuleRepository;
import pl.pollub.pari.common.domain.TimeRange;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.repository.ReservationRepository;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.spot.domain.SpotUnit;
import pl.pollub.pari.spot.repository.ParkingSpotRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Owner's weekly schedule (F03) and the check "can this time range be booked?". Reservations that already hold the slot
 * are not looked at here; the reservation service checks them separately.
 */
@Service
public class AvailabilityService {

    /** One entry of the weekly schedule an owner submits. */
    public record RuleCommand(int dayOfWeek, LocalTime startTime, LocalTime endTime, LocalDate validFrom, LocalDate validTo) {
    }

    private final AvailabilityRuleRepository rules;
    private final AvailabilityExceptionRepository exceptions;
    private final ParkingSpotRepository spots;
    private final ReservationRepository reservations;
    private final ZoneId zone;

    public AvailabilityService(AvailabilityRuleRepository rules, AvailabilityExceptionRepository exceptions,
                               ParkingSpotRepository spots, ReservationRepository reservations, ZoneId businessZone) {
        this.rules = rules;
        this.exceptions = exceptions;
        this.spots = spots;
        this.reservations = reservations;
        this.zone = businessZone;
    }

    /**
     * True when the owner allows bookings for the whole range. A blocked period inside the range always wins. A space with
     * no weekly rules is open all the time; otherwise every local calendar day of the range must be covered by a weekly
     * rule or by an extra open period.
     */
    @Transactional(readOnly = true)
    public boolean isAvailable(ParkingSpot spot, TimeRange range) {
        UUID spotId = spot.getId();
        // a block of one space is checked per space when booking (isUnitBlocked), a listing-wide one closes everything here
        if (exceptions.existsListingWideOverlapping(spotId, ExceptionKind.BLOCKED, range.start(), range.end())) {
            return false;
        }
        List<AvailabilityRule> weekly = rules.findBySpotIdOrderByDayOfWeekAscStartTimeAsc(spotId);
        if (weekly.isEmpty()) {
            return true;
        }
        List<TimeRange> openPeriods = exceptions.findOverlapping(spotId, range.start(), range.end()).stream()
                .filter(e -> e.getKind() == ExceptionKind.OPEN)
                .map(AvailabilityException::getPeriod)
                .toList();
        for (TimeRange day : splitByLocalDay(range)) {
            boolean covered = weekly.stream().anyMatch(r -> r.covers(day, zone))
                    || openPeriods.stream().anyMatch(p -> !p.start().isAfter(day.start()) && !p.end().isBefore(day.end()));
            if (!covered) {
                return false;
            }
        }
        return true;
    }

    /** True when the owner blocked this space, or the whole listing, inside the range (PARI-65). */
    @Transactional(readOnly = true)
    public boolean isUnitBlocked(UUID spotId, UUID unitId, TimeRange range) {
        return exceptions.existsOverlappingForUnit(spotId, ExceptionKind.BLOCKED, unitId, range.start(), range.end());
    }

    /** Replaces the whole weekly schedule. An empty list means "open all the time". */
    @Transactional
    public List<AvailabilityRule> replaceWeeklyRules(UUID ownerId, UUID spotId, List<RuleCommand> commands) {
        ParkingSpot spot = ownedSpot(ownerId, spotId);
        rules.deleteAllBySpotId(spotId);
        rules.flush();
        List<AvailabilityRule> created = new ArrayList<>();
        for (RuleCommand c : commands) {
            created.add(rules.save(new AvailabilityRule(spot, c.dayOfWeek(), c.startTime(), c.endTime(),
                    c.validFrom(), c.validTo())));
        }
        return created;
    }

    @Transactional(readOnly = true)
    public List<AvailabilityRule> weeklyRules(UUID spotId) {
        return rules.findBySpotIdOrderByDayOfWeekAscStartTimeAsc(spotId);
    }

    /** "Zablokuj termin": the owner blocks a period. Refused while a reservation still holds a slot inside it. */
    @Transactional
    public AvailabilityException blockPeriod(UUID ownerId, UUID spotId, TimeRange period, String reason) {
        return blockPeriod(ownerId, spotId, period, reason, null);
    }

    /**
     * Blocks a period of one space ({@code unitId}) or of the whole listing ({@code unitId == null}, a closure of every
     * space). Refused while a reservation of the space (or, for a closure, of any space) still holds a slot inside it.
     */
    @Transactional
    public AvailabilityException blockPeriod(UUID ownerId, UUID spotId, TimeRange period, String reason, UUID unitId) {
        ParkingSpot spot = ownedSpot(ownerId, spotId);
        if (unitId == null) {
            if (reservations.existsOverlapping(spotId, ReservationStatus.SLOT_HOLDING, period.start(), period.end())) {
                throw new ConflictException("PERIOD_HAS_RESERVATIONS", "A reservation already exists in this period");
            }
            return exceptions.save(new AvailabilityException(spot, period, ExceptionKind.BLOCKED, reason));
        }
        SpotUnit unit = spot.liveUnits().stream().filter(u -> unitId.equals(u.getId())).findFirst()
                .orElseThrow(() -> new NotFoundException("Space not found"));
        if (reservations.existsOverlappingOnUnit(unitId, ReservationStatus.SLOT_HOLDING, period.start(), period.end())) {
            throw new ConflictException("PERIOD_HAS_RESERVATIONS", "A reservation already exists in this period");
        }
        return exceptions.save(new AvailabilityException(spot, unit, period, reason));
    }

    @Transactional
    public void unblockPeriod(UUID ownerId, UUID spotId, UUID exceptionId) {
        ownedSpot(ownerId, spotId);
        AvailabilityException exception = exceptions.findByIdAndSpotId(exceptionId, spotId)
                .orElseThrow(() -> new NotFoundException("Blocked period not found"));
        exceptions.delete(exception);
    }

    private ParkingSpot ownedSpot(UUID ownerId, UUID spotId) {
        return spots.findByIdAndOwnerId(spotId, ownerId)
                .orElseThrow(() -> new NotFoundException("Parking spot not found"));
    }

    /** Cuts a range at local midnight, because a weekly rule covers one local day at a time. */
    private List<TimeRange> splitByLocalDay(TimeRange range) {
        List<TimeRange> parts = new ArrayList<>();
        Instant cursor = range.start();
        while (cursor.isBefore(range.end())) {
            Instant nextMidnight = cursor.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
            Instant partEnd = nextMidnight.isBefore(range.end()) ? nextMidnight : range.end();
            parts.add(new TimeRange(cursor, partEnd));
            cursor = partEnd;
        }
        return parts;
    }
}
