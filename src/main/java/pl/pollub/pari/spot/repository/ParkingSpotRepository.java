package pl.pollub.pari.spot.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pl.pollub.pari.spot.domain.ParkingSpot;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ParkingSpotRepository extends JpaRepository<ParkingSpot, UUID> {

    List<ParkingSpot> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    Optional<ParkingSpot> findByIdAndOwnerId(UUID id, UUID ownerId);

    /** The spot with its row locked until the transaction ends: bookings of one listing are decided one at a time (PARI-65). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ParkingSpot s where s.id = :id")
    Optional<ParkingSpot> findByIdForUpdate(@Param("id") UUID id);

    /** One row of a map search: the spot id, its distance from the search point and how many of its spaces are free. */
    interface SpotDistance {
        UUID getId();

        double getDistanceMeters();

        long getFreeUnits();
    }

    /**
     * Map search (F11, F12): active spots within a radius, optionally cheaper than a price, only verified ones, and free in
     * the requested time range. Premium owners come first, then the nearest. Availability rules and blocked slots are not
     * checked here, the service does that on the returned candidates.
     */
    @Query(value = """
            SELECT s.id AS "id",
                   ST_Distance(s.location, CAST(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326) AS geography)) AS "distanceMeters",
                   f.n AS "freeUnits"
            FROM parking_spots s
            CROSS JOIN LATERAL (
                SELECT count(*) AS n FROM spot_units u
                WHERE u.spot_id = s.id AND u.removed_at IS NULL
                  AND NOT EXISTS (
                        SELECT 1 FROM reservations r
                        WHERE r.unit_id = u.id
                          AND r.status IN ('PENDING_PAYMENT', 'PENDING_APPROVAL', 'CONFIRMED', 'IN_PROGRESS')
                          AND tstzrange(r.starts_at, r.blocked_until, '[)') && tstzrange(:start, :end, '[)'))
            ) f
            WHERE s.status = 'ACTIVE'
              AND ST_DWithin(s.location, CAST(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326) AS geography), :radiusMeters)
              AND s.hourly_price_minor <= :maxPriceMinor
              AND (s.is_verified OR NOT :onlyVerified)
              AND f.n > 0
            ORDER BY CASE WHEN EXISTS (
                           SELECT 1 FROM premium_subscriptions p
                           WHERE p.owner_id = s.owner_id AND p.status IN ('ACTIVE', 'CANCELLED') AND p.expires_at > now())
                     THEN 0 ELSE 1 END,
                     "distanceMeters"
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<SpotDistance> searchNearby(@Param("lat") double lat,
                                    @Param("lng") double lng,
                                    @Param("radiusMeters") double radiusMeters,
                                    @Param("maxPriceMinor") long maxPriceMinor,
                                    @Param("onlyVerified") boolean onlyVerified,
                                    @Param("start") Instant start,
                                    @Param("end") Instant end,
                                    @Param("limit") int limit,
                                    @Param("offset") int offset);

    /** Convenience wrapper: {@code null} filters mean "no restriction". */
    default List<SpotDistance> search(double lat, double lng, double radiusMeters, Long maxPriceMinor,
                                      boolean onlyVerified, Instant start, Instant end, int limit, int offset) {
        // A range in 1970 never overlaps a reservation, so it disables the time filter.
        Instant from = start != null && end != null ? start : Instant.EPOCH;
        Instant to = start != null && end != null ? end : Instant.EPOCH.plusSeconds(1);
        return searchNearby(lat, lng, radiusMeters, maxPriceMinor != null ? maxPriceMinor : Long.MAX_VALUE,
                onlyVerified, from, to, limit, offset);
    }
}
