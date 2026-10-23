package pl.pollub.pari.spot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import pl.pollub.pari.availability.domain.AvailabilityException;
import pl.pollub.pari.availability.domain.ExceptionKind;
import pl.pollub.pari.common.domain.TimeRange;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.support.DbTestBase;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(SpotCalendarService.class)
class SpotCalendarServiceTest extends DbTestBase {

    private static final Instant T10 = Instant.parse("2030-01-07T10:00:00Z");
    private static final Instant T12 = Instant.parse("2030-01-07T12:00:00Z");
    private static final Instant T14 = Instant.parse("2030-01-07T14:00:00Z");
    private static final Instant T16 = Instant.parse("2030-01-07T16:00:00Z");

    @Autowired
    SpotCalendarService service;

    User owner;
    User driver;
    User other;
    ParkingSpot spot;
    Reservation booked;

    @BeforeEach
    void setUp() {
        owner = persistUser(Role.OWNER);
        driver = persistUser(Role.DRIVER);
        other = persistUser(Role.DRIVER);
        spot = persistSpot(owner, 51.2465, 22.5684, 1000);
        booked = persistReservation(spot, driver, persistVehicle(driver), T10, T12);
        booked.markPaid(true);
        em.persist(new AvailabilityException(spot, new TimeRange(T14, T16), ExceptionKind.BLOCKED, "private use"));
        em.flush();
    }

    @Test
    void listsReservationsAndBlockedPeriodsEarliestFirst() {
        List<SpotCalendarService.BookedSlot> slots = service.bookedSlots(owner.getId(), spot.getId(), T10.minusSeconds(3600), T16.plusSeconds(3600));

        assertThat(slots).extracting(SpotCalendarService.BookedSlot::status).containsExactly("CONFIRMED", "BLOCKED");
        assertThat(slots.get(0).reservationId()).isEqualTo(booked.getId());
        assertThat(slots.get(1).blockId()).isNotNull();
    }

    @Test
    void cancelledAndRejectedReservationsAreLeftOut() {
        Reservation cancelled = persistReservation(spot, other, persistVehicle(other), T12, T14);
        cancelled.markPaid(true);
        cancelled.cancel("x", Instant.parse("2029-12-01T00:00:00Z"));
        em.flush();

        assertThat(service.bookedSlots(owner.getId(), spot.getId(), T10.minusSeconds(3600), T16.plusSeconds(3600)))
                .extracting(SpotCalendarService.BookedSlot::status).containsExactly("CONFIRMED", "BLOCKED");
    }

    @Test
    void onlyIntervalsInsideTheWindowAreReturnedAndTouchingDoesNotCount() {
        // window [12:00, 14:00) touches the reservation (ends 12:00) and the block (starts 14:00) but overlaps neither
        assertThat(service.bookedSlots(owner.getId(), spot.getId(), T12, T14)).isEmpty();
        assertThat(service.bookedSlots(owner.getId(), spot.getId(), T12.minusSeconds(1), T14.plusSeconds(1))).hasSize(2);
    }

    @Test
    void othersDoNotLearnWhoseReservationAnIntervalIs() {
        assertThat(service.bookedSlots(other.getId(), spot.getId(), T10, T16).get(0).reservationId()).isNull();
        assertThat(service.bookedSlots(driver.getId(), spot.getId(), T10, T16).get(0).reservationId()).isEqualTo(booked.getId());
        assertThat(service.bookedSlots(other.getId(), spot.getId(), T10, T16).get(1).blockId()).isNull();
    }

    @Test
    void theWindowMustBeValidAndTheSpaceMustBeVisible() {
        assertThatThrownBy(() -> service.bookedSlots(owner.getId(), spot.getId(), T12, T10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.bookedSlots(owner.getId(), UUID.randomUUID(), T10, T12)).isInstanceOf(NotFoundException.class);

        spot.archive();
        em.flush();
        assertThatThrownBy(() -> service.bookedSlots(owner.getId(), spot.getId(), T10, T12)).isInstanceOf(NotFoundException.class);
    }
}
