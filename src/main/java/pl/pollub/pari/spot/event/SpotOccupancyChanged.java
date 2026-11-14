package pl.pollub.pari.spot.event;

import pl.pollub.pari.spot.domain.Occupancy;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.spot.domain.SpotUnit;

import java.time.Instant;
import java.util.UUID;

/**
 * A space became free or occupied (check-in, check-out). Pushed to map clients over SSE after the commit.
 * {@code occupancy} is the state of the whole listing (OCCUPIED only when every space is); {@code unitId} and
 * {@code unitLabel} name the space that changed, {@code occupiedUnits} of {@code totalUnits} are occupied (PARI-65).
 */
public record SpotOccupancyChanged(UUID spotId, Occupancy occupancy, Instant at, UUID unitId, String unitLabel,
                                   int occupiedUnits, int totalUnits) {

    /** An event without a named space. */
    public SpotOccupancyChanged(UUID spotId, Occupancy occupancy, Instant at) {
        this(spotId, occupancy, at, null, null, 0, 0);
    }

    public static SpotOccupancyChanged of(ParkingSpot spot, SpotUnit unit, Instant at) {
        return new SpotOccupancyChanged(spot.getId(), spot.getOccupancy(), at, unit.getId(), unit.getLabel(),
                spot.occupiedUnitCount(), spot.liveUnits().size());
    }
}
