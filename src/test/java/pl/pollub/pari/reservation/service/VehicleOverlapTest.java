package pl.pollub.pari.reservation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.service.ReservationService.BookCommand;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.support.ServiceTestBase;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** One vehicle, one reservation at a time, at any spot (PARI-62). */
class VehicleOverlapTest extends ServiceTestBase {

    private static final String VISA = "pm_card_visa";

    @Autowired
    ReservationService service;

    User owner;
    User driver;
    Vehicle vehicle;
    ParkingSpot spotA;
    ParkingSpot spotB;

    @BeforeEach
    void setUp() {
        owner = persistUser(Role.OWNER);
        driver = persistUser(Role.DRIVER);
        vehicle = persistVehicle(driver);
        spotA = persistSpot(owner, 51.2465, 22.5684, 1000);
        spotB = persistSpot(owner, 51.2470, 22.5690, 1000);
        em.flush();
    }

    @Test
    void theSameVehicleCannotBeBookedAtAnotherSpotAtTheSameTime() {
        ReservationView first = book(spotA, T10, T12).reservation();

        assertThatThrownBy(() -> book(spotB, T10, T12))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("VEHICLE_TIME_CONFLICT");
                    assertThat(e.getDetails()).containsEntry("conflictingReservationId", first.id())
                            .containsEntry("conflictingStart", T10).containsEntry("conflictingEnd", T12);
                });
    }

    @Test
    void aPartiallyOverlappingIntervalIsRefused() {
        book(spotA, T10, T12);

        assertThatThrownBy(() -> book(spotB, T12.minusSeconds(1800), T14))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("VEHICLE_TIME_CONFLICT"));
        assertThatThrownBy(() -> book(spotB, T10.minusSeconds(1800), T10.plusSeconds(1800)))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("VEHICLE_TIME_CONFLICT"));
    }

    @Test
    void backToBackReservationsOfOneVehicleAreAllowed() {
        book(spotA, T10, T12);

        assertThat(book(spotB, T12, T14).reservation().status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void anotherDriversVehicleIsNotAffected() {
        book(spotA, T10, T12);
        User other = persistUser(Role.DRIVER);
        Vehicle otherVehicle = persistVehicle(other);

        assertThat(service.book(other.getId(), new BookCommand(spotB.getId(), otherVehicle.getId(), T10, T12, VISA))
                .reservation().status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void finishedReservationsDoNotOccupyTheVehicle() {
        Reservation cancelled = reservation(spotA, T10, T12);
        cancelled.cancel("changed plans", NOW);
        em.flush();
        Reservation rejected = reservation(spotA, T10, T12);
        rejected.markPaid(false);
        rejected.reject("no", NOW);
        em.flush();
        Reservation expired = reservation(spotA, T10, T12);
        expired.markPaid(false);
        expired.expire(NOW);
        em.flush();
        Reservation completed = reservation(spotA, T10, T12);
        completed.markPaid(true);
        completed.checkIn(T10);
        completed.checkOut(T12);
        em.flush();

        assertThat(book(spotB, T10, T12).reservation().status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void everyOccupyingStatusBlocksTheVehicle() {
        Reservation pendingPayment = reservation(spotA, T10, T12);
        em.flush();
        assertConflict(spotB, T10, T12);
        pendingPayment.cancel("x", NOW);
        em.flush();

        Reservation pendingApproval = reservation(spotA, T10, T12);
        pendingApproval.markPaid(false);
        em.flush();
        assertConflict(spotB, T10, T12);
        pendingApproval.reject("x", NOW);
        em.flush();

        Reservation confirmed = reservation(spotA, T10, T12);
        confirmed.markPaid(true);
        em.flush();
        assertConflict(spotB, T10, T12);
    }

    @Test
    void theDatabaseRefusesTwoOverlappingReservationsOfOneVehicle() {
        // the safety net for simultaneous requests that both pass the application check
        reservation(spotA, T10, T12);
        em.flush();
        reservation(spotB, T10, T12);

        assertThatThrownBy(() -> em.flush()).hasStackTraceContaining("reservations_vehicle_no_overlap");
    }

    private void assertConflict(ParkingSpot spot, Instant start, Instant end) {
        assertThatThrownBy(() -> book(spot, start, end))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("VEHICLE_TIME_CONFLICT"));
    }

    private Reservation reservation(ParkingSpot spot, Instant start, Instant end) {
        return persistReservation(spot, driver, vehicle, start, end);
    }

    private ReservationService.BookingResult book(ParkingSpot spot, Instant start, Instant end) {
        return service.book(driver.getId(), new BookCommand(spot.getId(), vehicle.getId(), start, end, VISA));
    }
}
