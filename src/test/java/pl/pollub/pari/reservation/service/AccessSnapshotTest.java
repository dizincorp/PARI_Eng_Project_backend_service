package pl.pollub.pari.reservation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.service.ReservationService.BookCommand;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.spot.service.SpotService;
import pl.pollub.pari.spot.service.SpotService.UpdateSpotCommand;
import pl.pollub.pari.spot.service.SpotView;
import pl.pollub.pari.support.ServiceTestBase;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PARI-85 and PARI-66: the owner's gate PIN is a separate, encrypted property of the space, and every confirmed
 * reservation keeps its own snapshot of the PIN and the access instructions.
 */
class AccessSnapshotTest extends ServiceTestBase {

    private static final String VISA = "pm_card_visa";

    @Autowired
    ReservationService reservations;
    @Autowired
    SpotService spots;

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
        spot.setAccessInstructions("Second gate on the left");
        spot.changeOwnerGatePin("4921");
        em.flush();
    }

    @Test
    void anAutoAcceptedReservationKeepsTheGatePinAndInstructionsOfTheMomentOfBooking() {
        ReservationView view = book(T10, T12);

        assertThat(view.ownerGatePinSnapshot()).isEqualTo("4921");
        assertThat(view.accessInstructionsSnapshot()).isEqualTo("Second gate on the left");
        // the generated check-in code is a different thing and is still generated
        assertThat(view.pinCode()).matches("\\d{4}");
    }

    @Test
    void aManuallyAcceptedReservationTakesTheValuesValidAtAcceptanceNotAtRequestTime() {
        spot.setAutoAccept(false);
        ReservationView pending = book(T10, T12);
        assertThat(pending.ownerGatePinSnapshot()).isNull(); // not confirmed yet, nothing snapshotted

        spot.changeOwnerGatePin("7788");
        em.flush();
        reservations.accept(owner.getId(), pending.id());

        ReservationView forOwner = reservations.get(owner.getId(), pending.id());
        assertThat(forOwner.ownerGatePinSnapshot()).isEqualTo("7788");
        assertThat(reservations.get(driver.getId(), pending.id()).accessInstructionsSnapshot()).isEqualTo("Second gate on the left");
    }

    @Test
    void editingOrClearingTheSpotLaterDoesNotChangeExistingReservations() {
        ReservationView view = book(T10, T12);

        spots.update(owner.getId(), spot.getId(), new UpdateSpotCommand("Garage", null, Money.pln(1000), null, null, 500, 250, 200,
                "New instructions", null, null, "0000"));
        assertThat(reservations.get(driver.getId(), view.id()).ownerGatePinSnapshot()).isEqualTo("4921");

        spots.update(owner.getId(), spot.getId(), new UpdateSpotCommand("Garage", null, Money.pln(1000), null, null, 500, 250, 200,
                null, null, null, null));
        ReservationView after = reservations.get(driver.getId(), view.id());
        assertThat(after.ownerGatePinSnapshot()).isEqualTo("4921");
        assertThat(after.accessInstructionsSnapshot()).isEqualTo("Second gate on the left");

        // a later booking gets the new (cleared) values
        ReservationView later = book(T14, T14.plusSeconds(7200));
        assertThat(later.ownerGatePinSnapshot()).isNull();
        assertThat(later.accessInstructionsSnapshot()).isNull();
    }

    @Test
    void aReservationConfirmedBeforeTheSnapshotsExistedReturnsNullNotTheCurrentSpotValues() {
        Reservation legacy = persistReservation(spot, driver, vehicle, T10, T12);
        legacy.markPaid(true);
        em.createNativeQuery("update reservations set owner_gate_pin_snapshot = null, access_instructions_snapshot = null where id = ?1")
                .setParameter(1, legacy.getId()).executeUpdate();
        em.flush();
        em.clear();

        ReservationView view = reservations.get(driver.getId(), legacy.getId());
        assertThat(view.ownerGatePinSnapshot()).isNull();
        assertThat(view.accessInstructionsSnapshot()).isNull();
    }

    @Test
    void theSnapshotsAreShownToTheDriverAndTheOwnerOnly() {
        Reservation reservation = persistReservation(spot, driver, vehicle, T10, T12);
        reservation.markPaid(true);
        em.flush();

        assertThat(ReservationView.of(reservation, driver.getId()).ownerGatePinSnapshot()).isEqualTo("4921");
        assertThat(ReservationView.of(reservation, owner.getId()).ownerGatePinSnapshot()).isEqualTo("4921");
        ReservationView stranger = ReservationView.of(reservation, java.util.UUID.randomUUID());
        assertThat(stranger.ownerGatePinSnapshot()).isNull();
        assertThat(stranger.accessInstructionsSnapshot()).isNull();
    }

    @Test
    void theGatePinIsStoredEncryptedAndOnlyTheOwnerSeesItOnTheSpot() {
        Object stored = em.createNativeQuery("select owner_gate_pin from parking_spots where id = ?1")
                .setParameter(1, spot.getId()).getSingleResult();
        assertThat(stored).isNotNull().isNotEqualTo("4921");
        assertThat(String.valueOf(stored)).doesNotContain("4921");

        SpotView ownerView = spots.get(spot.getId(), owner.getId());
        assertThat(ownerView.ownerGatePin()).isEqualTo("4921");
        assertThat(spots.get(spot.getId(), driver.getId()).ownerGatePin()).isNull();
        assertThat(spots.get(spot.getId(), null).ownerGatePin()).isNull();
    }

    @Test
    void aBlankPinClearsItAndALongerEditRoundTrips() {
        spot.changeOwnerGatePin("   ");
        em.flush();
        em.clear();
        assertThat(spots.get(spot.getId(), owner.getId()).ownerGatePin()).isNull();

        em.find(ParkingSpot.class, spot.getId()).changeOwnerGatePin(" 55 66 "); // the spot is detached after clear()
        em.flush();
        em.clear();
        assertThat(spots.get(spot.getId(), owner.getId()).ownerGatePin()).isEqualTo("55 66");
    }

    private ReservationView book(java.time.Instant start, java.time.Instant end) {
        return reservations.book(driver.getId(), new BookCommand(spot.getId(), vehicle.getId(), start, end, VISA)).reservation();
    }
}
