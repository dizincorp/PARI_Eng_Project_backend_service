package pl.pollub.pari.reservation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.reservation.domain.BookingType;
import pl.pollub.pari.reservation.service.ReservationService.BookCommand;
import pl.pollub.pari.reservation.service.ReservationService.BookingResult;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.spot.domain.SpotUnit;
import pl.pollub.pari.spot.service.SpotSearchService;
import pl.pollub.pari.spot.service.SpotSearchService.SearchCriteria;
import pl.pollub.pari.spot.service.SpotSearchService.SearchHit;
import pl.pollub.pari.support.ServiceTestBase;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PARI-65: booking and searching a listing that has several bookable spaces. */
class UnitBookingTest extends ServiceTestBase {

    private static final String VISA = "pm_card_visa";
    private static final double LAT = 51.2465;
    private static final double LNG = 22.5684;

    @Autowired
    ReservationService service;
    @Autowired
    SpotSearchService search;

    User owner;
    ParkingSpot spot;
    SpotUnit one;
    SpotUnit two;

    @BeforeEach
    void setUp() {
        owner = persistUser(Role.OWNER);
        spot = persistSpot(owner, LAT, LNG, 1000);
        two = spot.addUnit(null);
        em.flush();
        one = spot.liveUnits().get(0);
    }

    private BookingResult book(UUID unitId, Instant start, Instant end) {
        User driver = persistUser(Role.DRIVER);
        Vehicle vehicle = persistVehicle(driver);
        return service.book(driver.getId(), new BookCommand(spot.getId(), vehicle.getId(), start, end, VISA,
                BookingType.HOURLY, null, unitId));
    }

    @Test
    void bookingWithoutASpaceAssignsTheFirstFreeOneAndReturnsItsIdAndLabel() {
        ReservationView first = book(null, T10, T12).reservation();
        ReservationView second = book(null, T10, T12).reservation();

        assertThat(first.unitId()).isEqualTo(one.getId());
        assertThat(first.unitLabel()).isEqualTo("#1");
        assertThat(second.unitId()).isEqualTo(two.getId());
        assertThat(second.unitLabel()).isEqualTo("#2");
    }

    @Test
    void whenEverySpaceIsTakenTheBookingIsRefused() {
        book(null, T10, T12);
        book(null, T10, T12);

        assertThatThrownBy(() -> book(null, T10.plusSeconds(1800), T14))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("SPOT_ALREADY_RESERVED"));
    }

    @Test
    void aDriverCanChooseTheSecondSpaceWhileTheFirstIsFree() {
        ReservationView chosen = book(two.getId(), T10, T12).reservation();

        assertThat(chosen.unitId()).isEqualTo(two.getId());
        assertThat(chosen.unitLabel()).isEqualTo("#2");
        // the first space is still free, so an auto-assigned booking gets it
        assertThat(book(null, T10, T12).reservation().unitLabel()).isEqualTo("#1");
    }

    @Test
    void aChosenSpaceThatIsTakenIsRefusedEvenIfASiblingIsFree() {
        book(two.getId(), T10, T12);

        assertThatThrownBy(() -> book(two.getId(), T10.plusSeconds(1800), T14))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("SPOT_ALREADY_RESERVED"));
    }

    @Test
    void theBufferAfterABookingBlocksOnlyThatSpace() {
        book(one.getId(), T10, T12);

        // T12 is inside the unpaid hour of space #1, but space #2 is free
        assertThat(book(null, T12, T14).reservation().unitLabel()).isEqualTo("#2");
    }

    @Test
    void aSpaceThatIsNotPartOfTheListingIsNotFound() {
        ParkingSpot other = persistSpot(owner, LAT + 0.01, LNG, 1000);
        em.flush();

        assertThatThrownBy(() -> book(other.liveUnits().get(0).getId(), T10, T12)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> book(UUID.randomUUID(), T10, T12)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aRemovedSpaceCannotBeBookedButTheOtherOneStillCan() {
        spot.removeUnit(one, NOW);
        em.flush();

        assertThatThrownBy(() -> book(one.getId(), T10, T12)).isInstanceOf(NotFoundException.class);
        assertThat(book(null, T10, T12).reservation().unitLabel()).isEqualTo("#2");
    }

    @Test
    void aSingleSpaceListingKeepsWorkingWithoutAUnitId() {
        ParkingSpot single = persistSpot(owner, LAT + 0.02, LNG, 1000);
        em.flush();
        User driver = persistUser(Role.DRIVER);

        ReservationView view = service.book(driver.getId(), new BookCommand(single.getId(), persistVehicle(driver).getId(),
                T10, T12, VISA)).reservation();

        assertThat(view.unitLabel()).isEqualTo("#1");
        assertThat(view.unitId()).isEqualTo(single.liveUnits().get(0).getId());
    }

    @Test
    void searchShowsAListingWhileASpaceIsFreeAndCountsTheFreeOnes() {
        book(null, T10, T12);

        List<SearchHit> partly = search(T10, T12);
        assertThat(partly).hasSize(1);
        assertThat(partly.get(0).freeUnits()).isEqualTo(1);

        book(null, T10, T12);
        assertThat(search(T10, T12)).isEmpty();
        // another day is free in both spaces
        assertThat(search(T10.plusSeconds(86400), T12.plusSeconds(86400)).get(0).freeUnits()).isEqualTo(2);
    }

    @Test
    void searchWithoutATimeRangeCountsEverySpace() {
        book(null, T10, T12);

        assertThat(search(null, null).get(0).freeUnits()).isEqualTo(2);
    }

    private List<SearchHit> search(Instant start, Instant end) {
        return search.search(new SearchCriteria(LAT, LNG, 1000, null, false, start, end, 0, 20), null);
    }
}
