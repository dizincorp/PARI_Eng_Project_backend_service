package pl.pollub.pari.review;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.service.ReservationService;
import pl.pollub.pari.review.service.ReviewService;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.support.ServiceTestBase;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PARI-59: a review follows a real check-out (manual or automatic), is unique per reservation and maps back to it. */
@Import(ReviewService.class)
class ReviewAfterCheckoutTest extends ServiceTestBase {

    @Autowired
    ReservationService reservations;
    @Autowired
    ReviewService reviews;

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
    void aManualCheckOutMakesTheReservationReviewable() {
        Reservation r = inProgress(NOW.minusSeconds(3600), NOW.plusSeconds(3600));

        reservations.checkOut(driver.getId(), r.getId());
        ReviewService.ReviewView view = reviews.create(driver.getId(), r.getId(), 4, "ok");

        assertThat(view.reservationId()).isEqualTo(r.getId());
        assertThat(spot.getReviewCount()).isEqualTo(1);
        assertThat(spot.getAvgRating()).isEqualByComparingTo("4.00");
    }

    @Test
    void anAutomaticCheckOutMakesTheReservationReviewable() {
        Reservation r = inProgress(NOW.minusSeconds(4 * 3600), NOW.minusSeconds(3 * 3600));

        assertThat(reservations.autoCheckOut(r.getId(), NOW)).isTrue();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(r.getCheckInAt()).isNotNull();
        assertThat(r.getCheckOutAt()).isNotNull();

        assertThat(reviews.create(driver.getId(), r.getId(), 2, null).reservationId()).isEqualTo(r.getId());
        assertThat(spot.getAvgRating()).isEqualByComparingTo("2.00");
    }

    @Test
    void aDuplicateReviewOfACompletedReservationIsAlreadyReviewedNotNotCompleted() {
        Reservation r = inProgress(NOW.minusSeconds(3600), NOW.plusSeconds(3600));
        reservations.checkOut(driver.getId(), r.getId());
        reviews.create(driver.getId(), r.getId(), 5, null);

        assertThatThrownBy(() -> reviews.create(driver.getId(), r.getId(), 1, null))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("ALREADY_REVIEWED"));
        assertThat(spot.getReviewCount()).isEqualTo(1);
        assertThat(spot.getAvgRating()).isEqualByComparingTo("5.00");
    }

    @Test
    void everyReviewInTheSpotListCarriesItsOwnReservationId() {
        Reservation first = inProgress(NOW.minusSeconds(3600), NOW.plusSeconds(3600));
        reservations.checkOut(driver.getId(), first.getId());
        reviews.create(driver.getId(), first.getId(), 5, "first");

        Reservation second = inProgress(NOW.plusSeconds(86_400 - 3600), NOW.plusSeconds(86_400 + 3600));
        // the second session runs a day later: move the clock-independent state by completing it directly
        second.checkOut(NOW.plusSeconds(86_400));
        em.flush();
        reviews.create(driver.getId(), second.getId(), 3, "second");

        ReviewService.ReviewPage page = reviews.listForSpot(spot.getId(), 0, 10);
        assertThat(page.content()).extracting(ReviewService.ReviewView::reservationId)
                .containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(page.content()).filteredOn(v -> v.reservationId().equals(first.getId()))
                .singleElement().satisfies(v -> assertThat(v.comment()).isEqualTo("first"));
        assertThat(page.content()).filteredOn(v -> v.reservationId().equals(second.getId()))
                .singleElement().satisfies(v -> assertThat(v.comment()).isEqualTo("second"));
    }

    /** A paid reservation of the driver whose session is running; check-in at the start of the period. */
    private Reservation inProgress(Instant start, Instant end) {
        Reservation r = persistReservation(spot, driver, vehicle, start, end);
        r.markPaid(true);
        r.checkIn(start.plusSeconds(1));
        em.flush();
        return r;
    }
}
