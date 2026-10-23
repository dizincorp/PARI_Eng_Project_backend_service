package pl.pollub.pari.spot.event;

import pl.pollub.pari.spot.domain.Occupancy;

import java.time.Instant;
import java.util.UUID;

/** A space became free or occupied (check-in, check-out). Pushed to map clients over SSE after the commit. */
public record SpotOccupancyChanged(UUID spotId, Occupancy occupancy, Instant at) {
}
