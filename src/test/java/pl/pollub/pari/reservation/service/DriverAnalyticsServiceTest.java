package pl.pollub.pari.reservation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.exception.ForbiddenException;
import pl.pollub.pari.payment.domain.Payment;
import pl.pollub.pari.payment.domain.PaymentType;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.support.DbTestBase;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(DriverAnalyticsService.class)
class DriverAnalyticsServiceTest extends DbTestBase {

    private static final Instant D1 = Instant.parse("2030-01-07T10:00:00Z");

    @Autowired
    DriverAnalyticsService service;

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
    void aDriverWithoutBookingsGetsZeros() {
        DriverAnalyticsService.DriverAnalytics a = service.analytics(driver.getId());

        assertThat(a.totalSpent()).isEqualTo(Money.ZERO);
        assertThat(a.totalSaved()).isEqualTo(Money.ZERO);
        assertThat(a.totalBookingsCount()).isZero();
        assertThat(a.activeBookingsCount()).isZero();
        assertThat(a.loyaltyPoints()).isZero();
    }

    @Test
    void countsBookingsAndSumsWhatWasReallyPaid() {
        Reservation completed = booking(0);
        completed.markPaid(true);
        completed.checkIn(D1.plusSeconds(60));
        completed.checkOut(D1.plusSeconds(7000));
        charge(completed, PaymentType.CHARGE, 2100, true);

        Reservation upcoming = booking(1);
        upcoming.markPaid(true);
        charge(upcoming, PaymentType.CHARGE, 2100, true);

        Reservation cancelled = booking(2);
        cancelled.markPaid(true);
        charge(cancelled, PaymentType.CHARGE, 2100, true);
        cancelled.cancel("x", Instant.parse("2029-12-01T00:00:00Z"));
        charge(cancelled, PaymentType.REFUND, 2100, true);

        Reservation declined = booking(3);
        charge(declined, PaymentType.CHARGE, 2100, false);
        driver.addLoyaltyPoints(50);
        em.flush();

        DriverAnalyticsService.DriverAnalytics a = service.analytics(driver.getId());

        assertThat(a.totalBookingsCount()).isEqualTo(2);
        assertThat(a.activeBookingsCount()).isEqualTo(1);
        assertThat(a.totalSpent()).isEqualTo(Money.pln(4200));
        assertThat(a.loyaltyPoints()).isEqualTo(50);
    }

    @Test
    void aDriverWithoutCompletedSessionsGetsAnEmptyTableAndNoAverage() {
        booking(0).markPaid(true);
        em.flush();

        DriverAnalyticsService.Statistics s = service.statistics(driver.getId(), 0, 20);

        assertThat(s.items()).isEmpty();
        assertThat(s.totalItems()).isZero();
        assertThat(s.summary().completedReservations()).isZero();
        assertThat(s.summary().totalSpent()).isEqualTo(Money.ZERO);
        assertThat(s.summary().totalParkingDurationMinutes()).isZero();
        assertThat(s.summary().averageRating()).isNull();
        assertThat(s.summary().reviewedReservations()).isZero();
    }

    @Test
    void completedSessionsAreListedNewestFirstWithTheirPaymentAndReview() {
        Reservation older = completedBooking(driver, 0, 120);          // 120 minutes, reviewed 2
        charge(older, PaymentType.CHARGE, 3250, true);
        review(older, 2);
        Reservation newer = completedBooking(driver, 1, 60);           // 60 minutes, not reviewed, penalty and a refund
        charge(newer, PaymentType.CHARGE, 2000, true);
        charge(newer, PaymentType.PENALTY, 500, true);
        charge(newer, PaymentType.REFUND, 100, true);
        charge(newer, PaymentType.CHARGE, 999, false);                  // a failed attempt is not money paid
        Reservation upcoming = booking(5);                               // not completed: never listed
        upcoming.markPaid(true);
        em.flush();

        DriverAnalyticsService.Statistics s = service.statistics(driver.getId(), 0, 20);

        assertThat(s.items()).extracting(DriverAnalyticsService.SessionRow::reservationId)
                .containsExactly(newer.getId(), older.getId());
        DriverAnalyticsService.SessionRow second = s.items().get(1);
        assertThat(second.spotName()).isEqualTo(spot.getName());
        assertThat(second.durationMinutes()).isEqualTo(120);
        assertThat(second.amountPaid()).isEqualTo(Money.pln(3250));
        assertThat(second.reviewed()).isTrue();
        assertThat(second.rating()).isEqualTo(2);
        DriverAnalyticsService.SessionRow first = s.items().get(0);
        assertThat(first.amountPaid()).isEqualTo(Money.pln(2400));
        assertThat(first.reviewed()).isFalse();
        assertThat(first.rating()).isNull();
        assertThat(s.summary().completedReservations()).isEqualTo(2);
        assertThat(s.summary().totalSpent()).isEqualTo(Money.pln(5650));
        assertThat(s.summary().totalParkingDurationMinutes()).isEqualTo(180);
        assertThat(s.summary().averageRating()).isEqualByComparingTo("2.00");
        assertThat(s.summary().reviewedReservations()).isEqualTo(1);
    }

    @Test
    void theTableIsPagedWhileTheSummaryCoversEverySession() {
        for (int i = 0; i < 3; i++) {
            charge(completedBooking(driver, i, 30), PaymentType.CHARGE, 1000, true);
        }

        DriverAnalyticsService.Statistics page1 = service.statistics(driver.getId(), 1, 2);

        assertThat(page1.items()).hasSize(1);
        assertThat(page1.page()).isEqualTo(1);
        assertThat(page1.totalItems()).isEqualTo(3);
        assertThat(page1.summary().completedReservations()).isEqualTo(3);
        assertThat(page1.summary().totalSpent()).isEqualTo(Money.pln(3000));
        assertThat(service.statistics(driver.getId(), 5, 2).items()).isEmpty();
    }

    @Test
    void anotherDriversSessionsAreNeverIncluded() {
        User other = persistUser(Role.DRIVER);
        Vehicle otherVehicle = persistVehicle(other);
        Reservation theirs = persistReservation(spot, other, otherVehicle, D1.plusSeconds(30 * 86_400L), D1.plusSeconds(30 * 86_400L + 7200));
        theirs.markPaid(true);
        theirs.checkIn(D1.plusSeconds(30 * 86_400L + 60));
        theirs.checkOut(D1.plusSeconds(30 * 86_400L + 3660));
        charge(theirs, PaymentType.CHARGE, 9900, true);
        review(theirs, 5);
        Reservation mine = completedBooking(driver, 0, 45);
        charge(mine, PaymentType.CHARGE, 1500, true);
        em.flush();

        DriverAnalyticsService.Statistics s = service.statistics(driver.getId(), 0, 20);

        assertThat(s.items()).extracting(DriverAnalyticsService.SessionRow::reservationId).containsExactly(mine.getId());
        assertThat(s.summary().totalSpent()).isEqualTo(Money.pln(1500));
        assertThat(s.summary().averageRating()).isNull();
        assertThat(service.statistics(other.getId(), 0, 20).items()).extracting(DriverAnalyticsService.SessionRow::reservationId)
                .containsExactly(theirs.getId());
    }

    @Test
    void onlyDriversHaveDriverStatistics() {
        assertThatThrownBy(() -> service.analytics(owner.getId())).isInstanceOf(ForbiddenException.class);
    }

    /** A paid reservation on the given day that was checked in one minute after its start and ran for {@code minutes}. */
    private Reservation completedBooking(User who, int day, long minutes) {
        Reservation r = booking(day);
        r.markPaid(true);
        Instant in = r.getPeriod().start().plusSeconds(60);
        r.checkIn(in);
        r.checkOut(in.plusSeconds(minutes * 60));
        em.flush();
        return r;
    }

    @Test
    void durationIsTheActualElapsedTimeRoundedUpToWholeMinutes() {
        completedAfterSeconds(0, 30);     // a very short session is one minute, not zero
        completedAfterSeconds(1, 60);     // exactly one minute
        completedAfterSeconds(2, 61);     // one second more rounds up
        completedAfterSeconds(3, 0);      // check-out in the same second as the check-in
        em.flush();

        DriverAnalyticsService.Statistics s = service.statistics(driver.getId(), 0, 20);

        // newest first: day 3, 2, 1, 0
        assertThat(s.items()).extracting(DriverAnalyticsService.SessionRow::durationMinutes).containsExactly(0L, 2L, 1L, 1L);
        assertThat(s.summary().totalParkingDurationMinutes()).isEqualTo(4);
    }

    @Test
    void theDurationDoesNotDependOnTheBookedIntervalOrThePrice() {
        // booked for two hours, the driver stayed 45 minutes
        completedAfterSeconds(0, 45 * 60);
        em.flush();

        assertThat(service.statistics(driver.getId(), 0, 20).items()).singleElement()
                .satisfies(row -> assertThat(row.durationMinutes()).isEqualTo(45));
    }

    @Test
    void aSessionWithoutValidTimestampsHasNoDurationAndIsNotInTheTotal() {
        Reservation good = completedAfterSeconds(0, 600);
        Reservation noCheckIn = completedAfterSeconds(1, 600);
        Reservation noCheckOut = completedAfterSeconds(2, 600);
        Reservation backwards = completedAfterSeconds(3, 600);
        em.flush();
        jdbc.update("update reservations set check_in_at = null where id = ?", noCheckIn.getId());
        jdbc.update("update reservations set check_out_at = null where id = ?", noCheckOut.getId());
        jdbc.update("update reservations set check_out_at = check_in_at - interval '5 minutes' where id = ?", backwards.getId());
        em.clear();

        DriverAnalyticsService.Statistics s = service.statistics(driver.getId(), 0, 20);

        assertThat(s.items()).hasSize(4);
        assertThat(s.items()).filteredOn(row -> row.reservationId().equals(good.getId()))
                .singleElement().satisfies(row -> assertThat(row.durationMinutes()).isEqualTo(10));
        assertThat(s.items()).filteredOn(row -> !row.reservationId().equals(good.getId()))
                .extracting(DriverAnalyticsService.SessionRow::durationMinutes).containsOnlyNulls();
        assertThat(s.summary().totalParkingDurationMinutes()).isEqualTo(10);
        assertThat(s.summary().completedReservations()).isEqualTo(4);
    }

    @Test
    void theTotalIsTheSumOverAllPagesAndEqualsTheSumOfTheItems() {
        long expected = 0;
        for (int i = 0; i < 5; i++) {
            completedAfterSeconds(i, 90L * (i + 1));    // 2, 3, 5, 6, 8 minutes after rounding up
            expected += (90L * (i + 1) + 59) / 60;
        }
        em.flush();

        DriverAnalyticsService.Statistics first = service.statistics(driver.getId(), 0, 2);
        DriverAnalyticsService.Statistics last = service.statistics(driver.getId(), 2, 2);

        assertThat(first.items()).hasSize(2);
        assertThat(first.summary().totalParkingDurationMinutes()).isEqualTo(expected).isEqualTo(24);
        assertThat(last.summary().totalParkingDurationMinutes()).isEqualTo(expected);
        assertThat(service.statistics(driver.getId(), 0, 100).items().stream()
                .mapToLong(DriverAnalyticsService.SessionRow::durationMinutes).sum()).isEqualTo(expected);
    }

    /** A paid reservation on its own day, checked in at its start and checked out the given number of seconds later. */
    private Reservation completedAfterSeconds(int day, long seconds) {
        Reservation r = booking(day);
        r.markPaid(true);
        r.checkIn(r.getPeriod().start());
        r.checkOut(r.getPeriod().start().plusSeconds(seconds));
        return r;
    }

    private void review(Reservation reservation, int rating) {
        em.persist(new pl.pollub.pari.review.domain.Review(reservation, reservation.getDriver(), owner, spot, rating, null));
        em.flush();
    }

    private Reservation booking(int day) {
        Instant start = D1.plusSeconds(day * 86_400L);
        return persistReservation(spot, driver, vehicle, start, start.plusSeconds(7200));
    }

    private void charge(Reservation reservation, PaymentType type, long minor, boolean succeeded) {
        Payment payment = new Payment(reservation, type, Money.pln(minor), null);
        if (succeeded) {
            payment.markSucceeded();
        } else {
            payment.markFailed();
        }
        em.persist(payment);
        em.flush();
    }
}
