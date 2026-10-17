package pl.pollub.pari.spot.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.premium.domain.PremiumSubscription;
import pl.pollub.pari.spot.domain.Amenity;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.spot.repository.ParkingSpotRepository.SpotDistance;
import pl.pollub.pari.support.DbTestBase;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ParkingSpotRepositoryTest extends DbTestBase {

    // city centres
    private static final double LUBLIN_LAT = 51.2465;
    private static final double LUBLIN_LNG = 22.5684;
    private static final double WARSAW_LAT = 52.2297;
    private static final double WARSAW_LNG = 21.0122;

    @Autowired
    ParkingSpotRepository spots;

    @Autowired
    AmenityRepository amenities;

    private List<java.util.UUID> ids(List<SpotDistance> result) {
        return result.stream().map(SpotDistance::getId).toList();
    }

    @Test
    void savesPhotosAmenitiesAndMoney() {
        User owner = persistUser(Role.OWNER);
        ParkingSpot spot = persistSpot(owner, LUBLIN_LAT, LUBLIN_LNG, 1250);
        spot.addPhoto("spots/1/a.jpg");
        spot.addPhoto("spots/1/b.jpg");
        Set<Amenity> tags = Set.copyOf(amenities.findByCodeIn(List.of("COVERED", "GATE")));
        spot.getAmenities().addAll(tags);
        flushAndClear();

        ParkingSpot loaded = spots.findById(spot.getId()).orElseThrow();

        assertThat(loaded.getHourlyPrice()).isEqualTo(Money.pln(1250));
        assertThat(loaded.getLocation().lat()).isEqualTo(LUBLIN_LAT);
        assertThat(loaded.getAddress().city()).isEqualTo("Lublin");
        assertThat(loaded.getDimensions().heightCm()).isEqualTo(200);
        assertThat(loaded.getPhotos()).extracting("storageKey").containsExactly("spots/1/a.jpg", "spots/1/b.jpg");
        assertThat(loaded.getAmenities()).extracting("code").containsExactlyInAnyOrder("COVERED", "GATE");
        assertThat(spots.findByIdAndOwnerId(spot.getId(), owner.getId())).isPresent();
        assertThat(spots.findByIdAndOwnerId(spot.getId(), persistUser(Role.OWNER).getId())).isEmpty();
    }

    @Test
    void geographyColumnIsDerivedFromLatLng() {
        ParkingSpot spot = persistSpot(persistUser(Role.OWNER), LUBLIN_LAT, LUBLIN_LNG, 1000);
        flushAndClear();

        Double lat = jdbc.queryForObject("select ST_Y(location::geometry) from parking_spots where id = ?", Double.class, spot.getId());
        Double lng = jdbc.queryForObject("select ST_X(location::geometry) from parking_spots where id = ?", Double.class, spot.getId());

        assertThat(lat).isEqualTo(LUBLIN_LAT);
        assertThat(lng).isEqualTo(LUBLIN_LNG);
    }

    @Test
    void activeStatusRequiresTheOwnershipDeclarationInTheDatabaseToo() {
        ParkingSpot spot = new ParkingSpot(persistUser(Role.OWNER), "x", null,
                new pl.pollub.pari.spot.domain.Address("S", "Lublin", "20-001", "PL"),
                new pl.pollub.pari.spot.domain.GeoPoint(LUBLIN_LAT, LUBLIN_LNG),
                new pl.pollub.pari.spot.domain.Dimensions(500, 250, null), Money.pln(1000));
        em.persist(spot);
        flushAndClear();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.update("update parking_spots set status = 'ACTIVE' where id = ?", spot.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void searchFindsOnlySpotsInsideTheRadiusNearestFirst() {
        User owner = persistUser(Role.OWNER);
        ParkingSpot centre = persistSpot(owner, LUBLIN_LAT, LUBLIN_LNG, 1000);
        ParkingSpot nearby = persistSpot(owner, LUBLIN_LAT + 0.01, LUBLIN_LNG, 1000);   // about 1.1 km north
        ParkingSpot warsaw = persistSpot(owner, WARSAW_LAT, WARSAW_LNG, 1000);          // about 150 km away
        flushAndClear();

        List<SpotDistance> inLublin = spots.search(LUBLIN_LAT, LUBLIN_LNG, 5_000, null, false, null, null, 20, 0);
        assertThat(ids(inLublin)).containsExactly(centre.getId(), nearby.getId());
        assertThat(inLublin.get(0).getDistanceMeters()).isLessThan(1);
        assertThat(inLublin.get(1).getDistanceMeters()).isBetween(1_000.0, 1_300.0);

        List<SpotDistance> wide = spots.search(LUBLIN_LAT, LUBLIN_LNG, 300_000, null, false, null, null, 20, 0);
        assertThat(ids(wide)).containsExactly(centre.getId(), nearby.getId(), warsaw.getId());
    }

    @Test
    void searchSupportsPagingPriceAndVerifiedFilters() {
        User owner = persistUser(Role.OWNER);
        ParkingSpot cheap = persistSpot(owner, LUBLIN_LAT, LUBLIN_LNG, 500);
        ParkingSpot verified = persistSpot(owner, LUBLIN_LAT + 0.001, LUBLIN_LNG, 2000);
        verified.markVerified();
        ParkingSpot pricey = persistSpot(owner, LUBLIN_LAT + 0.002, LUBLIN_LNG, 5000);
        flushAndClear();

        assertThat(ids(spots.search(LUBLIN_LAT, LUBLIN_LNG, 5_000, 2_000L, false, null, null, 20, 0)))
                .containsExactly(cheap.getId(), verified.getId());
        assertThat(ids(spots.search(LUBLIN_LAT, LUBLIN_LNG, 5_000, null, true, null, null, 20, 0)))
                .containsExactly(verified.getId());
        assertThat(ids(spots.search(LUBLIN_LAT, LUBLIN_LNG, 5_000, null, false, null, null, 1, 1)))
                .containsExactly(verified.getId());
        assertThat(spots.search(LUBLIN_LAT, LUBLIN_LNG, 5_000, null, false, null, null, 20, 3)).isEmpty();
        assertThat(pricey.getId()).isNotNull();
    }

    @Test
    void searchSkipsInactiveSpots() {
        User owner = persistUser(Role.OWNER);
        ParkingSpot inactive = persistSpot(owner, LUBLIN_LAT, LUBLIN_LNG, 1000);
        inactive.deactivate();
        flushAndClear();

        assertThat(spots.search(LUBLIN_LAT, LUBLIN_LNG, 5_000, null, false, null, null, 20, 0)).isEmpty();
    }

    @Test
    void searchExcludesSpotsReservedInTheRequestedRange() {
        User owner = persistUser(Role.OWNER);
        User driver = persistUser(Role.DRIVER);
        Vehicle vehicle = persistVehicle(driver);
        ParkingSpot taken = persistSpot(owner, LUBLIN_LAT, LUBLIN_LNG, 1000);
        ParkingSpot free = persistSpot(owner, LUBLIN_LAT + 0.001, LUBLIN_LNG, 1000);
        persistReservation(taken, driver, vehicle, T10, T12);
        flushAndClear();

        assertThat(ids(spots.search(LUBLIN_LAT, LUBLIN_LNG, 5_000, null, false, T10.plusSeconds(1800), T12.plusSeconds(1800), 20, 0)))
                .containsExactly(free.getId());
        // the reservation ends exactly when the requested range starts, so the spot is free again
        assertThat(ids(spots.search(LUBLIN_LAT, LUBLIN_LNG, 5_000, null, false, T12, T14, 20, 0)))
                .containsExactly(taken.getId(), free.getId());
    }

    @Test
    void premiumOwnersAreListedFirstEvenWhenFarther() {
        User regular = persistUser(Role.OWNER);
        User premiumOwner = persistUser(Role.OWNER);
        ParkingSpot near = persistSpot(regular, LUBLIN_LAT, LUBLIN_LNG, 1000);
        ParkingSpot farPremium = persistSpot(premiumOwner, LUBLIN_LAT + 0.02, LUBLIN_LNG, 1000);
        Instant now = Instant.now();
        em.persist(new PremiumSubscription(premiumOwner, PremiumSubscription.MONTHLY, now.minus(Duration.ofDays(1)),
                now.plus(Duration.ofDays(29)), "sub_1"));
        flushAndClear();

        assertThat(ids(spots.search(LUBLIN_LAT, LUBLIN_LNG, 10_000, null, false, null, null, 20, 0)))
                .containsExactly(farPremium.getId(), near.getId());
    }
}
