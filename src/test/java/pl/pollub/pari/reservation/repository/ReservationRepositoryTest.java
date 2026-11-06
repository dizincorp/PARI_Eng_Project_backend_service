package pl.pollub.pari.reservation.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.payment.domain.Payment;
import pl.pollub.pari.payment.domain.PaymentType;
import pl.pollub.pari.payment.domain.Payout;
import pl.pollub.pari.payment.domain.PayoutStatus;
import pl.pollub.pari.payment.repository.PaymentRepository;
import pl.pollub.pari.payment.repository.PayoutRepository;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.support.DbTestBase;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationRepositoryTest extends DbTestBase {

    @Autowired
    ReservationRepository reservations;

    @Autowired
    PaymentRepository payments;

    @Autowired
    PayoutRepository payouts;

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
    }

    @Test
    void savesAReservationWithPriceSnapshotAndPeriod() {
        Reservation saved = persistReservation(spot, driver, vehicle, T10, T12);
        flushAndClear();

        Reservation loaded = reservations.findByCode(saved.getCode()).orElseThrow();

        assertThat(loaded.getPeriod().start()).isEqualTo(T10);
        assertThat(loaded.getPeriod().end()).isEqualTo(T12);
        assertThat(loaded.getStatus()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
        assertThat(loaded.getHourlyRate()).isEqualTo(Money.pln(1000));
        assertThat(loaded.getTotal()).isEqualTo(Money.pln(2100));
        assertThat(loaded.getFinalCost()).isNull();
    }

    @Test
    void overlappingReservationOfTheSameSpotIsRejectedByTheDatabase() {
        persistReservation(spot, driver, vehicle, T10, T12);
        em.flush();

        assertThatThrownBy(() -> reservations.saveAndFlush(newReservation(spot, persistUser(Role.DRIVER), vehicle, T10.plusSeconds(3600), T14)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("reservations_no_overlap");
    }

    @Test
    void adjacentReservationsAndOtherSpotsAreAllowed() {
        persistReservation(spot, driver, vehicle, T10, T12);
        persistReservation(spot, driver, vehicle, T12, T14);
        ParkingSpot other = persistSpot(owner, 51.25, 22.57, 1000);
        persistReservation(other, driver, persistVehicle(driver), T10, T12); // one vehicle cannot be in two places (PARI-62)

        em.flush();

        assertThat(reservations.count()).isEqualTo(3);
    }

    @Test
    void cancellingReleasesTheSlotForSomeoneElse() {
        Reservation first = persistReservation(spot, driver, vehicle, T10, T12);
        first.markPaid(true);
        em.flush();

        first.cancel("Changed plans", T10.minusSeconds(3600));
        em.flush();

        reservations.saveAndFlush(newReservation(spot, persistUser(Role.DRIVER), vehicle, T10, T12));
        assertThat(reservations.count()).isEqualTo(2);
    }

    @Test
    void anUnpaidReservationAlreadyHoldsTheSlot() {
        persistReservation(spot, driver, vehicle, T10, T12);
        em.flush();

        assertThat(reservations.existsOverlapping(spot.getId(), ReservationStatus.SLOT_HOLDING, T10, T12)).isTrue();
        assertThat(reservations.existsOverlapping(spot.getId(), ReservationStatus.SLOT_HOLDING, T12, T14)).isFalse();
    }

    @Test
    void findOverlappingReturnsReservationsInTimeOrder() {
        Reservation later = persistReservation(spot, driver, vehicle, T12, T14);
        Reservation earlier = persistReservation(spot, driver, vehicle, T10, T12);
        flushAndClear();

        List<Reservation> found = reservations.findOverlapping(spot.getId(), ReservationStatus.SLOT_HOLDING, T10, T14);

        assertThat(found).extracting(Reservation::getId).containsExactly(earlier.getId(), later.getId());
    }

    @Test
    void driverQueriesAndActiveSession() {
        Reservation upcoming = persistReservation(spot, driver, vehicle, T12, T14);
        upcoming.markPaid(true);
        Reservation running = persistReservation(spot, driver, vehicle, T10, T12);
        running.markPaid(true);
        running.checkIn(T10);
        flushAndClear();

        assertThat(reservations.findByDriverId(driver.getId(), PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);
        assertThat(reservations.findByDriverIdAndStatusIn(driver.getId(), EnumSet.of(ReservationStatus.CONFIRMED), PageRequest.of(0, 10)))
                .extracting(Reservation::getId).containsExactly(upcoming.getId());
        assertThat(reservations.findFirstByDriverIdAndStatusOrderByPeriodStartDesc(driver.getId(), ReservationStatus.IN_PROGRESS))
                .map(Reservation::getId).contains(running.getId());
    }

    @Test
    void ownerViewFiltersAreOptional() {
        Reservation r1 = persistReservation(spot, driver, vehicle, T10, T12);
        persistReservation(spot, driver, vehicle, T12, T14).markPaid(true);
        persistReservation(persistSpot(persistUser(Role.OWNER), 51.3, 22.6, 1000), driver, persistVehicle(driver), T10, T12);
        flushAndClear();

        PageRequest page = PageRequest.of(0, 10);
        assertThat(reservations.findForOwner(owner.getId(), null, null, null, null, page).getTotalElements()).isEqualTo(2);
        assertThat(reservations.findForOwner(owner.getId(), spot.getId(), ReservationStatus.PENDING_PAYMENT, null, null, page))
                .extracting(Reservation::getId).containsExactly(r1.getId());
        assertThat(reservations.findForOwner(owner.getId(), null, null, T12, null, page).getTotalElements()).isEqualTo(1);
        assertThat(reservations.findForOwner(owner.getId(), null, null, null, T12, page).getTotalElements()).isEqualTo(1);
    }

    @Test
    void schedulerQueries() {
        Reservation unpaid = persistReservation(spot, driver, vehicle, T10, T12);
        Reservation running = persistReservation(spot, driver, vehicle, T12, T14);
        running.markPaid(true);
        running.checkIn(T12);
        flushAndClear();

        Instant now = Instant.now();
        assertThat(reservations.findByStatusAndCreatedAtBefore(ReservationStatus.PENDING_PAYMENT, now.plusSeconds(60)))
                .extracting(Reservation::getId).containsExactly(unpaid.getId());
        assertThat(reservations.findByStatusAndCreatedAtBefore(ReservationStatus.PENDING_PAYMENT, now.minus(Duration.ofDays(1)))).isEmpty();
        assertThat(reservations.findByStatusAndPeriodEndBefore(ReservationStatus.IN_PROGRESS, T14.plusSeconds(1)))
                .extracting(Reservation::getId).containsExactly(running.getId());
        assertThat(reservations.findByStatusAndPeriodStartBetween(ReservationStatus.PENDING_PAYMENT, T10.minusSeconds(60), T10.plusSeconds(60)))
                .extracting(Reservation::getId).containsExactly(unpaid.getId());
        assertThat(reservations.findBySpotIdAndStatusInAndPeriodEndAfter(spot.getId(), ReservationStatus.SLOT_HOLDING, T12))
                .hasSize(1);
        assertThat(reservations.findByDriverIdAndStatusInAndPeriodEndAfter(driver.getId(), ReservationStatus.SLOT_HOLDING, T10))
                .hasSize(2);
    }

    @Test
    void paymentsAreIdempotentByProviderReference() {
        Reservation reservation = persistReservation(spot, driver, vehicle, T10, T12);
        payments.saveAndFlush(new Payment(reservation, PaymentType.CHARGE, Money.pln(2100), "pi_123"));

        assertThat(payments.findByProviderRef("pi_123")).isPresent();
        assertThatThrownBy(() -> payments.saveAndFlush(new Payment(reservation, PaymentType.CHARGE, Money.pln(2100), "pi_123")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void payoutsAreDueAndSummed() {
        Reservation r1 = persistReservation(spot, driver, vehicle, T10, T12);
        Reservation r2 = persistReservation(spot, driver, vehicle, T12, T14);
        Payout due = payouts.save(new Payout(r1, owner, Money.pln(2000), Money.pln(200), T12.plus(Duration.ofHours(24))));
        Payout notYet = payouts.save(new Payout(r2, owner, Money.pln(3000), Money.pln(300), T14.plus(Duration.ofHours(24))));
        flushAndClear();

        Instant afterFirst = T12.plus(Duration.ofHours(25));
        assertThat(payouts.findByStatusAndReleaseAtLessThanEqual(PayoutStatus.SCHEDULED, afterFirst))
                .extracting(Payout::getId).containsExactly(due.getId());
        assertThat(payouts.findByReservationId(r2.getId())).map(Payout::getId).contains(notYet.getId());
        assertThat(payouts.findByOwnerIdOrderByReleaseAtDesc(owner.getId(), PageRequest.of(0, 10)))
                .extracting(Payout::getId).containsExactly(notYet.getId(), due.getId());

        Instant from = T10.minus(Duration.ofDays(1));
        Instant to = T14.plus(Duration.ofDays(5));
        assertThat(payouts.sumNetMinor(owner.getId(), "SCHEDULED", from, to)).isEqualTo(1800 + 2700);
        assertThat(payouts.sumNetMinor(owner.getId(), "PAID", from, to)).isZero();
    }

    @Test
    void aReservationCanHaveOnlyOnePayout() {
        Reservation reservation = persistReservation(spot, driver, vehicle, T10, T12);
        payouts.saveAndFlush(new Payout(reservation, owner, Money.pln(2000), Money.pln(200), T12));

        assertThatThrownBy(() -> payouts.saveAndFlush(new Payout(reservation, owner, Money.pln(2000), Money.pln(200), T12)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
