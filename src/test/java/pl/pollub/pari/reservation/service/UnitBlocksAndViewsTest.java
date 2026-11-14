package pl.pollub.pari.reservation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import pl.pollub.pari.availability.domain.AvailabilityException;
import pl.pollub.pari.availability.service.AvailabilityService;
import pl.pollub.pari.common.domain.TimeRange;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.notification.domain.Notification;
import pl.pollub.pari.notification.domain.NotificationChannel;
import pl.pollub.pari.notification.repository.NotificationRepository;
import pl.pollub.pari.reservation.domain.BookingType;
import pl.pollub.pari.reservation.service.ReservationService.BookCommand;
import pl.pollub.pari.spot.domain.Occupancy;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.spot.domain.SpotUnit;
import pl.pollub.pari.spot.event.SpotOccupancyChanged;
import pl.pollub.pari.spot.service.SpotCalendarService;
import pl.pollub.pari.spot.service.SpotCalendarService.BookedSlot;
import pl.pollub.pari.spot.service.SpotSearchService;
import pl.pollub.pari.spot.service.SpotSearchService.SearchCriteria;
import pl.pollub.pari.spot.service.SpotView;
import pl.pollub.pari.support.ServiceTestBase;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PARI-65, part 3: blocks per space, the calendar, occupancy per space and the label in notifications and views. */
class UnitBlocksAndViewsTest extends ServiceTestBase {

    private static final String VISA = "pm_card_visa";
    private static final double LAT = 51.2465;
    private static final double LNG = 22.5684;

    @Autowired
    ReservationService reservations;
    @Autowired
    AvailabilityService availability;
    @Autowired
    SpotCalendarService calendar;
    @Autowired
    SpotSearchService search;
    @Autowired
    NotificationRepository notifications;

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

    private ReservationView book(UUID unitId, Instant start, Instant end) {
        User driver = persistUser(Role.DRIVER);
        return reservations.book(driver.getId(), new BookCommand(spot.getId(), persistVehicle(driver).getId(), start, end,
                VISA, BookingType.HOURLY, null, unitId)).reservation();
    }

    private AvailabilityException block(UUID unitId, Instant start, Instant end) {
        return availability.blockPeriod(owner.getId(), spot.getId(), new TimeRange(start, end), "Owner", unitId);
    }

    // ---- blocks

    @Test
    void aBlockOfOneSpaceDoesNotAffectItsSibling() {
        block(one.getId(), T10, T14);

        assertThat(book(null, T10, T12).unitLabel()).isEqualTo("#2");
        assertThatThrownBy(() -> book(one.getId(), T10, T12))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("SPOT_NOT_AVAILABLE"));
    }

    @Test
    void whenEverySpaceIsBlockedOrTakenTheBookingIsRefused() {
        block(one.getId(), T10, T14);
        book(two.getId(), T10, T12);

        assertThatThrownBy(() -> book(null, T10, T12)).isInstanceOf(ConflictException.class);
    }

    @Test
    void aListingWideClosureBlocksEverySpace() {
        block(null, T10, T14);

        assertThatThrownBy(() -> book(null, T10, T12))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("SPOT_NOT_AVAILABLE"));
        assertThatThrownBy(() -> book(two.getId(), T10, T12)).isInstanceOf(ConflictException.class);
    }

    @Test
    void aSpaceBlockIsRefusedOnlyWhenThatSpaceHasAReservation() {
        book(one.getId(), T10, T12);

        assertThatThrownBy(() -> block(one.getId(), T10, T14))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("PERIOD_HAS_RESERVATIONS"));
        assertThat(block(two.getId(), T10, T14).getUnit()).isEqualTo(two);
        // a closure of the whole listing is refused while any space is held
        assertThatThrownBy(() -> block(null, T10, T14)).isInstanceOf(ConflictException.class);
    }

    @Test
    void aBlockNeedsALiveSpaceOfTheSameListing() {
        ParkingSpot other = persistSpot(owner, LAT + 0.01, LNG, 1000);
        em.flush();

        assertThatThrownBy(() -> block(other.liveUnits().get(0).getId(), T10, T14)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> block(UUID.randomUUID(), T10, T14)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void searchCountsOnlyTheSpacesThatAreNotBlocked() {
        block(one.getId(), T10, T14);

        List<SpotSearchService.SearchHit> hits = search.search(new SearchCriteria(LAT, LNG, 1000, null, false, T10, T12, 0, 20), null);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).freeUnits()).isEqualTo(1);
        block(two.getId(), T10, T14);
        assertThat(search.search(new SearchCriteria(LAT, LNG, 1000, null, false, T10, T12, 0, 20), null)).isEmpty();
    }

    // ---- calendar

    @Test
    void theCalendarNamesTheSpaceOfEveryReservationAndBlock() {
        book(one.getId(), T10, T12);
        block(two.getId(), T10, T12);
        block(null, T12.plusSeconds(7200), T14.plusSeconds(7200));

        List<BookedSlot> slots = calendar.bookedSlots(owner.getId(), spot.getId(), T10.minusSeconds(3600), T14.plusSeconds(9000));

        assertThat(slots).extracting(BookedSlot::status).contains("CONFIRMED", "BLOCKED");
        assertThat(slots).filteredOn(s -> s.reservationId() != null).singleElement().satisfies(s -> {
            assertThat(s.unitId()).isEqualTo(one.getId());
            assertThat(s.unitLabel()).isEqualTo("#1");
        });
        assertThat(slots).filteredOn(s -> "BLOCKED".equals(s.status()) && s.unitId() != null).singleElement()
                .extracting(BookedSlot::unitLabel).isEqualTo("#2");
        assertThat(slots).filteredOn(s -> "BLOCKED".equals(s.status()) && s.unitId() == null).hasSize(1);
    }

    @Test
    void theCalendarCanBeFilteredToOneSpaceAndKeepsTheListingWideClosures() {
        book(one.getId(), T10, T12);
        block(two.getId(), T10, T12);
        block(null, T12.plusSeconds(7200), T14.plusSeconds(7200));

        List<BookedSlot> forTwo = calendar.bookedSlots(owner.getId(), spot.getId(), T10.minusSeconds(3600),
                T14.plusSeconds(9000), two.getId());

        assertThat(forTwo).extracting(BookedSlot::status).containsOnly("BLOCKED");
        assertThat(forTwo).hasSize(2);
        assertThatThrownBy(() -> calendar.bookedSlots(owner.getId(), spot.getId(), T10, T12, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- occupancy

    @Test
    void theListingIsOccupiedOnlyWhenEverySpaceIs() {
        spot.occupy(two);
        assertThat(spot.getOccupancy()).isEqualTo(Occupancy.FREE);
        assertThat(spot.occupiedUnitCount()).isEqualTo(1);
        SpotOccupancyChanged event = SpotOccupancyChanged.of(spot, two, NOW);
        assertThat(event.unitLabel()).isEqualTo("#2");
        assertThat(event.occupiedUnits()).isEqualTo(1);
        assertThat(event.totalUnits()).isEqualTo(2);

        spot.occupy(one);
        assertThat(spot.getOccupancy()).isEqualTo(Occupancy.OCCUPIED);

        spot.release(two);
        assertThat(spot.getOccupancy()).isEqualTo(Occupancy.FREE);
        SpotView view = SpotView.of(spot, owner.getId());
        assertThat(view.occupiedUnits()).isEqualTo(1);
        assertThat(view.totalUnits()).isEqualTo(2);
        assertThat(view.units()).extracting(SpotView.UnitRef::occupied).containsExactly(true, false);
    }

    @Test
    void aSingleSpaceListingStillFlipsWithCheckInAndCheckOut() {
        ParkingSpot single = persistSpot(owner, LAT + 0.02, LNG, 1000);

        single.occupy();
        assertThat(single.getOccupancy()).isEqualTo(Occupancy.OCCUPIED);
        single.release();
        assertThat(single.getOccupancy()).isEqualTo(Occupancy.FREE);
    }

    // ---- labels in notifications and views

    @Test
    void notificationsNameTheSpaceOfAListingWithSeveralSpaces() {
        ReservationView view = book(two.getId(), T10, T12);
        em.flush();

        List<Notification> forOwner = notifications.findByUserIdAndChannelOrderByCreatedAtDesc(owner.getId(),
                NotificationChannel.IN_APP, PageRequest.of(0, 10)).getContent();

        assertThat(forOwner).isNotEmpty().allSatisfy(n -> {
            assertThat(n.getPayload()).containsEntry("unitLabel", "#2");
            assertThat(n.getBody()).contains("(#2)");
        });
        assertThat(view.unitLabel()).isEqualTo("#2");
    }

    @Test
    void aSingleSpaceListingKeepsItsNotificationTextButCarriesTheLabelInThePayload() {
        ParkingSpot single = persistSpot(owner, LAT + 0.02, LNG, 1000);
        em.flush();
        User driver = persistUser(Role.DRIVER);
        reservations.book(driver.getId(), new BookCommand(single.getId(), persistVehicle(driver).getId(), T10, T12, VISA));
        em.flush();

        List<Notification> forOwner = notifications.findByUserIdAndChannelOrderByCreatedAtDesc(owner.getId(),
                NotificationChannel.IN_APP, PageRequest.of(0, 10)).getContent();

        assertThat(forOwner).isNotEmpty().allSatisfy(n -> {
            assertThat(n.getPayload()).containsEntry("unitLabel", "#1");
            assertThat(n.getBody()).doesNotContain("(#1)");
        });
    }
}
