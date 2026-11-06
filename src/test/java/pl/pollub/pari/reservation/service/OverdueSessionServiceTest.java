package pl.pollub.pari.reservation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import pl.pollub.pari.payment.domain.Payment;
import pl.pollub.pari.payment.domain.PaymentStatus;
import pl.pollub.pari.payment.domain.PaymentType;
import pl.pollub.pari.payment.repository.PaymentRepository;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.event.ReservationEvent;
import pl.pollub.pari.spot.domain.Occupancy;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.support.ServiceTestBase;
import pl.pollub.pari.user.domain.PaymentMethod;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Spot price is 10.00 PLN/h, the penalty multiplier 1.5 and the grace period 15 minutes; the clock is fixed at NOW. */
@Import(OverdueSessionService.class)
@RecordApplicationEvents
class OverdueSessionServiceTest extends ServiceTestBase {

    @Autowired
    OverdueSessionService overdue;
    @Autowired
    PaymentRepository payments;
    @Autowired
    ApplicationEvents events;

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
    void onlySessionsPastTheEndPlusGraceAreDue() {
        Reservation overdueOne = running(NOW.minusSeconds(3 * 3600), NOW.minusSeconds(30 * 60));
        // still inside the grace period (another space and another vehicle, so nothing overlaps)
        Reservation insideGrace = persistReservation(persistSpot(owner, 51.25, 22.57, 1000), driver, persistVehicle(driver),
                NOW.minusSeconds(3 * 3600), NOW.minusSeconds(10 * 60));
        insideGrace.markPaid(true);
        insideGrace.checkIn(NOW.minusSeconds(2 * 3600));
        persistReservation(spot, driver, vehicle, NOW.plusSeconds(86_400), NOW.plusSeconds(90_000)).markPaid(true); // not started
        em.flush();

        assertThat(overdue.findDueIds()).containsExactly(overdueOne.getId());
    }

    @Test
    void anOverdueSessionIsCompletedChargedAndBothSidesAreNotified() {
        PaymentMethod card = new PaymentMethod(driver, "pm_card_visa", "visa", "4242", 12, 2030);
        card.markDefault(true);
        em.persist(card);
        Reservation r = running(NOW.minusSeconds(3 * 3600), NOW.minusSeconds(30 * 60));
        spot.occupy();
        em.flush();

        assertThat(overdue.endSession(r.getId(), overdue.dueBefore())).isTrue();

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(r.getCheckOutAt()).isEqualTo(NOW);
        assertThat(r.overstayMinutes()).isEqualTo(30);
        assertThat(spot.getOccupancy()).isEqualTo(Occupancy.FREE);
        // 30 min at 10.00/h x 1.5 = 7.50
        assertThat(payments.findByReservationIdOrderByCreatedAtAsc(r.getId()))
                .extracting(Payment::getType, Payment::getStatus, p -> p.getAmount().amountMinor())
                .containsExactly(tuple(PaymentType.PENALTY, PaymentStatus.SUCCEEDED, 750L));
        List<ReservationEvent> published = events.stream(ReservationEvent.class).toList();
        assertThat(published).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(ReservationEvent.Type.AUTO_CHECKOUT);
            assertThat(e.driverId()).isEqualTo(driver.getId());
            assertThat(e.ownerId()).isEqualTo(owner.getId());
            assertThat(e.actorId()).isNull();
        });
    }

    @Test
    void aSessionTheDriverAlreadyEndedIsLeftAlone() {
        Reservation r = running(NOW.minusSeconds(3 * 3600), NOW.minusSeconds(30 * 60));
        r.checkOut(NOW.minusSeconds(600));
        em.flush();

        assertThat(overdue.endSession(r.getId(), overdue.dueBefore())).isFalse();
        assertThat(overdue.endSession(UUID.randomUUID(), overdue.dueBefore())).isFalse();
        assertThat(events.stream(ReservationEvent.class)).isEmpty();
    }

    @Test
    void aFailedPenaltyChargeDoesNotStopTheCheckOut() {
        Reservation r = running(NOW.minusSeconds(3 * 3600), NOW.minusSeconds(60 * 60)); // no saved card

        assertThat(overdue.endSession(r.getId(), overdue.dueBefore())).isTrue();

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(payments.findByReservationIdOrderByCreatedAtAsc(r.getId()))
                .extracting(Payment::getType, Payment::getStatus)
                .containsExactly(tuple(PaymentType.PENALTY, PaymentStatus.FAILED));
    }

    /** A paid reservation that the driver checked in to and never ended. */
    private Reservation running(Instant start, Instant end) {
        Reservation r = persistReservation(spot, driver, vehicle, start, end);
        r.markPaid(true);
        r.checkIn(start.plusSeconds(60));
        em.flush();
        return r;
    }
}
