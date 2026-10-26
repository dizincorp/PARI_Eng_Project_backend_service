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
    void onlyDriversHaveDriverStatistics() {
        assertThatThrownBy(() -> service.analytics(owner.getId())).isInstanceOf(ForbiddenException.class);
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
