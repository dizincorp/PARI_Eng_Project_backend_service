package pl.pollub.pari.reservation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.domain.TimeRange;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.service.OwnerAnalyticsService.Analytics;
import pl.pollub.pari.reservation.service.OwnerAnalyticsService.Period;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.support.ServiceTestBase;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The clock is fixed at 2029-12-01 00:00 UTC, which is 01:00 on 2029-12-01 in Warsaw. */
@Import(OwnerAnalyticsService.class)
class OwnerAnalyticsServiceTest extends ServiceTestBase {

    @Autowired
    OwnerAnalyticsService service;

    User owner;
    User driver;
    Vehicle vehicle;
    ParkingSpot spot;
    ParkingSpot secondSpot;

    @BeforeEach
    void setUp() {
        owner = persistUser(Role.OWNER);
        driver = persistUser(Role.DRIVER);
        vehicle = persistVehicle(driver);
        spot = persistSpot(owner, 51.2, 22.5, 1000);
        secondSpot = persistSpot(owner, 51.3, 22.6, 2000);
        em.flush();
    }

    @Test
    void anOwnerWithoutCompletedReservationsGetsZerosAndAFullDailyBreakdown() {
        Analytics a = service.analytics(owner.getId(), Period.LAST_7_DAYS);

        assertThat(a.netEarnings()).isEqualTo(Money.ZERO);
        assertThat(a.grossEarnings()).isEqualTo(Money.ZERO);
        assertThat(a.earningsChangePercent()).isEqualByComparingTo("0");
        assertThat(a.occupancyRatePercent()).isEqualByComparingTo("0");
        assertThat(a.averageHourlyRate()).isEqualTo(Money.ZERO);
        assertThat(a.activeSpotsCount()).isEqualTo(2);
        assertThat(a.totalSpotsCount()).isEqualTo(2);
        assertThat(a.dailyBreakdown()).hasSize(7);
        assertThat(a.dailyBreakdown().getFirst().date()).isEqualTo(LocalDate.parse("2029-11-25"));
        assertThat(a.dailyBreakdown().getLast().date()).isEqualTo(LocalDate.parse("2029-12-01"));
        assertThat(a.dailyBreakdown()).allSatisfy(d -> assertThat(d.amount()).isEqualTo(Money.ZERO));
    }

    @Test
    void earningsAreNetOfCommissionAndGroupedByCompletionDay() {
        completed(spot, "2029-11-30T10:00:00Z", "2029-11-30T12:00:00Z", 2000, 200);
        completed(spot, "2029-11-30T14:00:00Z", "2029-11-30T15:00:00Z", 1000, 100);
        completed(spot, "2029-11-28T10:00:00Z", "2029-11-28T11:00:00Z", 1000, 0);

        Analytics a = service.analytics(owner.getId(), Period.LAST_7_DAYS);

        assertThat(a.grossEarnings()).isEqualTo(Money.pln(4000));
        assertThat(a.netEarnings()).isEqualTo(Money.pln(3700));
        assertThat(a.dailyBreakdown()).filteredOn(d -> d.date().equals(LocalDate.parse("2029-11-30")))
                .singleElement().extracting("amount").isEqualTo(Money.pln(2700));
        assertThat(a.dailyBreakdown()).filteredOn(d -> d.date().equals(LocalDate.parse("2029-11-28")))
                .singleElement().extracting("amount").isEqualTo(Money.pln(1000));
        assertThat(a.averageHourlyRate()).isEqualTo(Money.pln(1000));
    }

    @Test
    void onlyCompletedReservationsOfTheOwnerCount() {
        Reservation confirmed = newReservation(spot, driver, vehicle, Instant.parse("2029-11-30T10:00:00Z"), Instant.parse("2029-11-30T11:00:00Z"));
        confirmed.markPaid(true);
        em.persist(confirmed);
        User otherOwner = persistUser(Role.OWNER);
        ParkingSpot foreign = persistSpot(otherOwner, 51.4, 22.7, 1000);
        completed(foreign, "2029-11-30T10:00:00Z", "2029-11-30T11:00:00Z", 1000, 100);
        em.flush();

        assertThat(service.analytics(owner.getId(), Period.LAST_30_DAYS).netEarnings()).isEqualTo(Money.ZERO);
    }

    @Test
    void changeComparesWithTheEqualLengthPeriodBefore() {
        completed(spot, "2029-11-30T10:00:00Z", "2029-11-30T11:00:00Z", 1200, 0);
        completed(spot, "2029-11-22T10:00:00Z", "2029-11-22T11:00:00Z", 1000, 0);

        assertThat(service.analytics(owner.getId(), Period.LAST_7_DAYS).earningsChangePercent()).isEqualByComparingTo("20.0");
    }

    @Test
    void changeIsUndefinedWhenThePreviousPeriodEarnedNothing() {
        completed(spot, "2029-11-30T10:00:00Z", "2029-11-30T11:00:00Z", 1200, 0);

        assertThat(service.analytics(owner.getId(), Period.LAST_7_DAYS).earningsChangePercent()).isNull();
    }

    @Test
    void currentMonthStartsOnTheFirstAndComparesWithTheMonthBefore() {
        completed(spot, "2029-12-01T00:10:00Z", "2029-12-01T00:20:00Z", 500, 0);
        completed(spot, "2029-11-01T10:00:00Z", "2029-11-01T11:00:00Z", 1000, 0);
        completed(spot, "2029-11-15T10:00:00Z", "2029-11-15T11:00:00Z", 9000, 0);

        Analytics a = service.analytics(owner.getId(), Period.CURRENT_MONTH);

        assertThat(a.dailyBreakdown()).hasSize(1);
        assertThat(a.netEarnings()).isEqualTo(Money.pln(500));
        // previous window is the same length as the current one: 1 November only
        assertThat(a.earningsChangePercent()).isEqualByComparingTo("-50.0");
    }

    @Test
    void occupancyIsReservedTimeOverTheTimeTheActiveSpotsCouldBeBooked() {
        // two active spots over 7 days = 336 spot-hours; 33.6 reserved hours would be 10 %
        completed(spot, "2029-11-26T00:00:00Z", "2029-11-27T09:36:00Z", 1000, 0);

        assertThat(service.analytics(owner.getId(), Period.LAST_7_DAYS).occupancyRatePercent()).isEqualByComparingTo("10.0");
    }

    @Test
    void anUnknownPeriodIsRejected() {
        assertThatThrownBy(() -> Period.parse("1y")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Period.parse("7d")).isEqualTo(Period.LAST_7_DAYS);
        assertThat(Period.parse("30d")).isEqualTo(Period.LAST_30_DAYS);
        assertThat(Period.parse("current_month")).isEqualTo(Period.CURRENT_MONTH);
    }

    private Reservation completed(ParkingSpot s, String start, String end, long spotCost, long commission) {
        Reservation r = new Reservation("RSV-A-" + System.nanoTime(), s, driver, vehicle,
                new TimeRange(Instant.parse(start), Instant.parse(end)), s.getHourlyPrice(), Money.pln(spotCost),
                Money.pln(0), Money.pln(commission));
        r.markPaid(true);
        r.checkIn(Instant.parse(start));
        r.checkOut(Instant.parse(end));
        em.persist(r);
        em.flush();
        return r;
    }
}
