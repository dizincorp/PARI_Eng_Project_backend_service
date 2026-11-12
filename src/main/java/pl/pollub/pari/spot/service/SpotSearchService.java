package pl.pollub.pari.spot.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.availability.service.AvailabilityService;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.domain.TimeRange;
import pl.pollub.pari.spot.domain.GeoPoint;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.spot.repository.ParkingSpotRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Map search for free parking spaces (F11, F12). */
@Service
public class SpotSearchService {

    public static final double MAX_RADIUS_METERS = 50_000;
    public static final int MAX_PAGE_SIZE = 50;

    /** Search parameters. {@code maxHourlyPrice}, {@code start} and {@code end} are optional; start and end go together. */
    public record SearchCriteria(double lat, double lng, double radiusMeters, Money maxHourlyPrice,
                                 boolean onlyVerified, Instant start, Instant end, int page, int size) {
    }

    /** A found space with its distance from the search point. */
    public record SearchHit(SpotView spot, double distanceMeters) {
    }

    private final ParkingSpotRepository spots;
    private final AvailabilityService availability;
    private final pl.pollub.pari.common.service.PlatformSettings settings;

    public SpotSearchService(ParkingSpotRepository spots, AvailabilityService availability,
                             pl.pollub.pari.common.service.PlatformSettings settings) {
        this.spots = spots;
        this.availability = availability;
        this.settings = settings;
    }

    /**
     * A booking of [start, end) also needs the unpaid buffer after its end to be free (PARI-69), so the query looks
     * for reservations overlapping [start, end + buffer), the same range that {@code book} checks.
     */
    private java.time.Instant searchEnd(SearchCriteria c) {
        return c.end() == null ? null
                : c.end().plus(java.time.Duration.ofMinutes(settings.integer("reservation_buffer_minutes")));
    }

    /**
     * Active spaces within the radius, premium owners first and then the nearest. When a time range is given, spaces with a
     * reservation in it are excluded by the query, and spaces the owner has not opened for the whole range are dropped here.
     * Because that last filter runs after paging, a page can contain fewer than {@code size} hits.
     */
    @Transactional(readOnly = true)
    public List<SearchHit> search(SearchCriteria c, UUID viewerId) {
        new GeoPoint(c.lat(), c.lng()); // validates the coordinate range
        if (c.radiusMeters() <= 0 || c.radiusMeters() > MAX_RADIUS_METERS) {
            throw new IllegalArgumentException("radius must be between 1 and " + (long) MAX_RADIUS_METERS + " meters");
        }
        if (c.page() < 0 || c.size() < 1 || c.size() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE + " and page must not be negative");
        }
        if ((c.start() == null) != (c.end() == null)) {
            throw new IllegalArgumentException("start and end must be given together");
        }
        TimeRange range = c.start() == null ? null : new TimeRange(c.start(), c.end());

        List<ParkingSpotRepository.SpotDistance> candidates = spots.search(c.lat(), c.lng(), c.radiusMeters(),
                c.maxHourlyPrice() == null ? null : c.maxHourlyPrice().amountMinor(), c.onlyVerified(),
                c.start(), searchEnd(c), c.size(), c.page() * c.size());

        Map<UUID, ParkingSpot> byId = spots.findAllById(candidates.stream().map(ParkingSpotRepository.SpotDistance::getId).toList())
                .stream().collect(Collectors.toMap(ParkingSpot::getId, Function.identity()));

        return candidates.stream()
                .filter(d -> range == null || availability.isAvailable(byId.get(d.getId()), range))
                .map(d -> new SearchHit(SpotView.of(byId.get(d.getId()), viewerId), d.getDistanceMeters()))
                .toList();
    }
}
