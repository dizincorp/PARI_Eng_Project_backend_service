package pl.pollub.pari.spot.domain;

import jakarta.persistence.Embeddable;

/** WGS 84 coordinates. The database derives a PostGIS geography column from lat and lng for radius searches. */
@Embeddable
public record GeoPoint(double lat, double lng) {

    public GeoPoint {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new IllegalArgumentException("coordinates out of range: " + lat + ", " + lng);
        }
    }
}
