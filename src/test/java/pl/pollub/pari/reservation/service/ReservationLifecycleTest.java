package pl.pollub.pari.reservation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.event.ReservationEvent;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.support.ServiceTestBase;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** No-show, buffer and reminders on the real schema; the clock is fixed at NOW. */
@Import({SessionReminderService.class})
@RecordApplicationEvents
class ReservationLifecycleTest extends ServiceTestBase {

    @Autowired
    ReservationService service;
    @Autowired
    SessionReminderService reminders;
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
    void aConfirmedReservationPastItsEndBecomesNoShow() {
        Reservation r = confirmed(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofHours(1)));

        assertThat(service.markNoShow(r.getId())).isTrue();

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
        assertThat(events.stream(ReservationEvent.class)).singleElement()
                .satisfies(e -> assertThat(e.type()).isEqualTo(ReservationEvent.Type.NO_SHOW));
    }

    @Test
    void aReservationThatHasNotEndedIsNotANoShow() {
        Reservation r = confirmed(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)));

        assertThat(service.markNoShow(r.getId())).isFalse();

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void aNoShowFreesTheSlotAndItsBuffer() {
        Instant start = NOW.minus(Duration.ofHours(3));
        Instant end = NOW.minus(Duration.ofHours(1));
        Reservation r = confirmed(start, end);
        r.applyBuffer(Duration.ofMinutes(180)); // the buffer would still block the spot
        em.flush();
        service.markNoShow(r.getId());
        em.flush();

        // a new reservation inside the old buffer is accepted by the exclusion constraint now
        persistReservation(spot, persistUser(Role.DRIVER), persistVehicle(driver), end.plus(Duration.ofMinutes(30)), end.plus(Duration.ofHours(1)));
        em.flush();
    }

    @Test
    void theBufferBlocksTheSpotAfterTheEndInTheDatabase() {
        Instant start = NOW.minus(Duration.ofHours(3));
        Instant end = NOW.minus(Duration.ofHours(1));
        Reservation r = confirmed(start, end);
        r.applyBuffer(Duration.ofMinutes(60));
        em.flush();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
            persistReservation(spot, persistUser(Role.DRIVER), persistVehicle(driver), end.plus(Duration.ofMinutes(30)), end.plus(Duration.ofHours(2)));
            em.flush();
        }).hasMessageContaining("reservations_no_overlap");
    }

    @Test
    void remindersBeforeTheEndAreSentOnce() {
        Reservation r = running(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofMinutes(10)));

        assertThat(reminders.sendDue(r.getId())).isEqualTo(1);
        assertThat(reminders.sendDue(r.getId())).isZero();

        assertThat(events.stream(ReservationEvent.class).map(ReservationEvent::type).toList())
                .containsExactly(ReservationEvent.Type.REMINDER_BEFORE_END);
    }

    @Test
    void remindersAtTheEndAndFinalAreSentOnceEach() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(50)));

        assertThat(reminders.sendDue(r.getId())).isEqualTo(2);
        assertThat(reminders.sendDue(r.getId())).isZero();

        List<ReservationEvent.Type> types = events.stream(ReservationEvent.class).map(ReservationEvent::type).toList();
        assertThat(types).containsExactlyInAnyOrder(ReservationEvent.Type.REMINDER_AT_END, ReservationEvent.Type.REMINDER_FINAL);
    }

    private Reservation confirmed(Instant start, Instant end) {
        Reservation r = persistReservation(spot, driver, vehicle, start, end);
        r.markPaid(true);
        em.flush();
        return r;
    }

    private Reservation running(Instant start, Instant end) {
        Reservation r = confirmed(start, end);
        r.checkIn(start.plusSeconds(60));
        em.flush();
        return r;
    }
}
