package pl.pollub.pari.review;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.review.service.ReviewService;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.support.DbTestBase;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(ReviewService.class)
class ReviewServiceTest extends DbTestBase {

    private static final Instant START = Instant.parse("2030-01-07T10:00:00Z");
    private static final Instant END = Instant.parse("2030-01-07T12:00:00Z");

    @Autowired
    ReviewService service;

    User owner;
    User driver;
    Vehicle vehicle;
    ParkingSpot spot;

    @BeforeEach
    void setUp() {
        owner = persistUser(Role.OWNER);
        driver = persistUser(Role.DRIVER);
        vehicle = persistVehicle(driver);
        spot = persistSpot(owner, 51.2465, 22.5684, 1000);
        em.flush();
    }

    @Test
    void reviewingACompletedReservationUpdatesTheSpotRating() {
        Reservation first = completed(driver);
        ReviewService.ReviewView view = service.create(driver.getId(), first.getId(), 5, " Easy entry ");

        assertThat(view.rating()).isEqualTo(5);
        assertThat(view.comment()).isEqualTo("Easy entry");
        assertThat(view.authorName()).matches("Test U\\.");
        assertThat(spot.getReviewCount()).isEqualTo(1);
        assertThat(spot.getAvgRating()).isEqualByComparingTo("5.00");

        User second = persistUser(Role.DRIVER);
        service.create(second.getId(), completed(second).getId(), 2, null);

        assertThat(spot.getReviewCount()).isEqualTo(2);
        assertThat(spot.getAvgRating()).isEqualByComparingTo("3.50");
        ReviewService.ReviewPage page = service.listForSpot(spot.getId(), 0, 10);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).extracting(ReviewService.ReviewView::rating).containsExactlyInAnyOrder(5, 2);
    }

    @Test
    void onlyCompletedReservationsCanBeReviewed() {
        Reservation confirmed = persistReservation(spot, driver, vehicle, START, END);
        confirmed.markPaid(true);
        em.flush();

        assertThatThrownBy(() -> service.create(driver.getId(), confirmed.getId(), 5, null))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("RESERVATION_NOT_COMPLETED"));
    }

    @Test
    void aSecondReviewOfTheSameReservationIsRefused() {
        Reservation reservation = completed(driver);
        service.create(driver.getId(), reservation.getId(), 4, null);

        assertThatThrownBy(() -> service.create(driver.getId(), reservation.getId(), 1, null))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("REVIEW_ALREADY_EXISTS"));
        assertThat(spot.getReviewCount()).isEqualTo(1);
    }

    @Test
    void onlyTheDriverOfTheReservationCanReviewIt() {
        Reservation reservation = completed(driver);

        assertThatThrownBy(() -> service.create(owner.getId(), reservation.getId(), 5, null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.create(persistUser(Role.DRIVER).getId(), reservation.getId(), 5, null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theRatingMustBeBetween1And5() {
        Reservation reservation = completed(driver);

        assertThatThrownBy(() -> service.create(driver.getId(), reservation.getId(), 6, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(driver.getId(), reservation.getId(), 0, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listingReviewsOfAnUnknownSpotIsNotFound() {
        assertThatThrownBy(() -> service.listForSpot(java.util.UUID.randomUUID(), 0, 10)).isInstanceOf(NotFoundException.class);
    }

    /** A finished reservation of the spot by the given driver (each at a different time so they do not overlap). */
    private Reservation completed(User who) {
        Vehicle v = who.equals(driver) ? vehicle : persistVehicle(who);
        long offsetDays = who.equals(driver) ? 0 : 1;
        Instant start = START.plusSeconds(offsetDays * 86_400);
        Reservation reservation = persistReservation(spot, who, v, start, start.plusSeconds(7200));
        reservation.markPaid(true);
        reservation.checkIn(start.plusSeconds(60));
        reservation.checkOut(start.plusSeconds(7000));
        em.flush();
        return reservation;
    }
}
