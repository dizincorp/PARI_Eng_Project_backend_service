package pl.pollub.pari.spot.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.common.service.PlatformSettings;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.repository.ReservationRepository;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.spot.domain.SpotUnit;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The owner (or an ADMIN) manages the bookable spaces of a listing (PARI-65, docs/adr/0006). A space with a reservation that
 * still holds it cannot be removed, and the last one cannot be removed at all (archive the listing instead).
 */
@Service
public class SpotUnitService {

    private final SpotService spots;
    private final ReservationRepository reservations;
    private final PlatformSettings settings;
    private final Clock clock;

    public SpotUnitService(SpotService spots, ReservationRepository reservations, PlatformSettings settings, Clock clock) {
        this.spots = spots;
        this.reservations = reservations;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    public List<SpotView.UnitRef> add(UUID userId, UUID spotId, String label) {
        ParkingSpot spot = spots.manageableSpot(userId, spotId);
        int max = settings.integer("max_units_per_spot");
        if (spot.liveUnits().size() >= max) {
            throw new ConflictException("SPOT_UNIT_LIMIT", "A listing can have at most " + max + " spaces",
                    Map.of("max", max));
        }
        requireFreeLabel(spot, label, null);
        spot.addUnit(label);
        spots.flushChanges();
        return SpotView.unitRefs(spot);
    }

    @Transactional
    public List<SpotView.UnitRef> rename(UUID userId, UUID spotId, UUID unitId, String label) {
        ParkingSpot spot = spots.manageableSpot(userId, spotId);
        SpotUnit unit = liveUnit(spot, unitId);
        requireFreeLabel(spot, label, unit);
        spot.renameUnit(unit, label);
        spots.flushChanges();
        return SpotView.unitRefs(spot);
    }

    @Transactional
    public List<SpotView.UnitRef> remove(UUID userId, UUID spotId, UUID unitId) {
        ParkingSpot spot = spots.manageableSpot(userId, spotId);
        SpotUnit unit = liveUnit(spot, unitId);
        if (spot.liveUnits().size() <= 1) {
            throw new ConflictException("SPOT_UNIT_LAST", "The last space of a listing cannot be removed; archive the listing instead");
        }
        if (reservations.existsByUnitIdAndStatusInAndBlockedUntilAfter(unitId, ReservationStatus.SLOT_HOLDING, clock.instant())) {
            throw new ConflictException("SPOT_UNIT_HAS_RESERVATIONS",
                    "The space has active or upcoming reservations and cannot be removed", Map.of("label", unit.getLabel()));
        }
        spot.removeUnit(unit, clock.instant());
        spots.flushChanges();
        return SpotView.unitRefs(spot);
    }

    private static SpotUnit liveUnit(ParkingSpot spot, UUID unitId) {
        return spot.liveUnits().stream().filter(u -> u.getId().equals(unitId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Space not found"));
    }

    private static void requireFreeLabel(ParkingSpot spot, String label, SpotUnit except) {
        if (label != null && !label.isBlank() && spot.hasLiveUnitLabel(label, except)) {
            throw new ConflictException("SPOT_UNIT_LABEL_TAKEN", "A space with the label " + label.trim() + " already exists");
        }
    }
}
