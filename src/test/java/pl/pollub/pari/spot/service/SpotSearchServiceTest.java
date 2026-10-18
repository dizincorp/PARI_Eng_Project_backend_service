package pl.pollub.pari.spot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pl.pollub.pari.availability.service.AvailabilityService;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.reservation.service.ReservationService;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.spot.service.SpotSearchService.SearchCriteria;
import pl.pollub.pari.spot.service.SpotSearchService.SearchHit;
import pl.pollub.pari.support.ServiceTestBase;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Centre of Lublin: 51.2465, 22.5684. */
class SpotSearchServiceTest extends ServiceTestBase {

    private static final double LAT = 51.2465;
    private static final double LNG = 22.5684;

    @Autowired
    SpotSearchService search;
    @Autowired
    ReservationService reservations;
    @Autowired
    AvailabilityService availability;

    User owner;
    ParkingSpot near;
    ParkingSpot farther;
    ParkingSpot cheap;
    ParkingSpot faraway;

    @BeforeEach
    void setUp() {
        owner = persistUser(Role.OWNER);
        near = persistSpot(owner, LAT + 0.001, LNG, 1000);       // ~110 m
        farther = persistSpot(owner, LAT + 0.01, LNG, 1000);     // ~1.1 km
        cheap = persistSpot(owner, LAT + 0.005, LNG, 400);       // ~550 m
        faraway = persistSpot(owner, 52.2297, 21.0122, 1000);    // Warsaw
        em.flush();
    }

    @Test
    void findsActiveSpacesInTheRadiusNearestFirst() {
        List<SearchHit> hits = search(2000, null, false, null, null);

        assertThat(hits).extracting(h -> h.spot().id()).containsExactly(near.getId(), cheap.getId(), farther.getId());
        assertThat(hits.get(0).distanceMeters()).isBetween(50.0, 200.0);
        assertThat(hits.get(2).distanceMeters()).isGreaterThan(hits.get(1).distanceMeters());
    }

    @Test
    void aSmallerRadiusAndAPriceLimitNarrowTheResult() {
        assertThat(search(300, null, false, null, null)).extracting(h -> h.spot().id()).containsExactly(near.getId());
        assertThat(search(2000, Money.pln(500), false, null, null)).extracting(h -> h.spot().id()).containsExactly(cheap.getId());
    }

    @Test
    void inactiveSpacesAreNotListed() {
        near.deactivate();

        assertThat(search(2000, null, false, null, null)).extracting(h -> h.spot().id())
                .doesNotContain(near.getId());
    }

    @Test
    void onlyVerifiedFiltersByTheBadge() {
        farther.markVerified();

        assertThat(search(2000, null, true, null, null)).extracting(h -> h.spot().id()).containsExactly(farther.getId());
    }

    @Test
    void spacesWithAReservationInTheRequestedTimeAreLeftOut() {
        User driver = persistUser(Role.DRIVER);
        Vehicle vehicle = persistVehicle(driver);
        reservations.book(driver.getId(), new ReservationService.BookCommand(near.getId(), vehicle.getId(), T10, T12, "pm_card_visa"));

        assertThat(search(2000, null, false, T10, T12)).extracting(h -> h.spot().id())
                .containsExactly(cheap.getId(), farther.getId());
        assertThat(search(2000, null, false, T12, T14)).extracting(h -> h.spot().id())
                .containsExactly(near.getId(), cheap.getId(), farther.getId());
    }

    @Test
    void spacesTheOwnerHasNotOpenedForTheRequestedTimeAreLeftOut() {
        availability.replaceWeeklyRules(owner.getId(), cheap.getId(), List.of(
                new AvailabilityService.RuleCommand(2, LocalTime.of(8, 0), LocalTime.of(18, 0), null, null))); // Tuesdays only

        // T10-T12 is Monday 2030-01-07
        assertThat(search(2000, null, false, T10, T12)).extracting(h -> h.spot().id())
                .containsExactly(near.getId(), farther.getId());
    }

    @Test
    void invalidParametersAreRejected() {
        assertThatThrownBy(() -> search(0, null, false, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> search(60_000, null, false, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> search(2000, null, false, T10, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("together");
        assertThatThrownBy(() -> search.search(new SearchCriteria(120, LNG, 1000, null, false, null, null, 0, 20), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> search.search(new SearchCriteria(LAT, LNG, 1000, null, false, null, null, 0, 500), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<SearchHit> search(double radius, Money maxPrice, boolean onlyVerified, Instant start, Instant end) {
        return search.search(new SearchCriteria(LAT, LNG, radius, maxPrice, onlyVerified, start, end, 0, 20), null);
    }
}
