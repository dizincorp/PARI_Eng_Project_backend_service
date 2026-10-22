package pl.pollub.pari.user.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.support.DbTestBase;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(VehicleService.class)
class VehicleServiceTest extends DbTestBase {

    @Autowired
    VehicleService service;

    User driver;
    User other;

    @BeforeEach
    void setUp() {
        driver = persistUser(Role.DRIVER);
        other = persistUser(Role.DRIVER);
        em.flush();
    }

    @Test
    void aVehicleIsStoredWithANormalisedPlateAndListedOnlyForItsOwner() {
        VehicleService.VehicleView created = service.create(driver.getId(), "  Toyota Yaris ", " wa 98765 ");

        assertThat(created.plateNumber()).isEqualTo("WA98765");
        assertThat(created.model()).isEqualTo("Toyota Yaris");
        assertThat(service.list(driver.getId())).extracting(VehicleService.VehicleView::id).containsExactly(created.id());
        assertThat(service.list(other.getId())).isEmpty();
    }

    @Test
    void theSamePlateCannotBeRegisteredTwiceButAnotherUserMay() {
        service.create(driver.getId(), "Yaris", "WA 98765");

        assertThatThrownBy(() -> service.create(driver.getId(), "Yaris", "wa98765"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("VEHICLE_ALREADY_EXISTS"));
        assertThat(service.create(other.getId(), "Yaris", "WA 98765").plateNumber()).isEqualTo("WA98765");
    }

    @Test
    void aVehicleIsDeletedByItsOwnerOnly() {
        UUID id = service.create(driver.getId(), "Yaris", "WA 98765").id();

        assertThatThrownBy(() -> service.delete(other.getId(), id)).isInstanceOf(NotFoundException.class);
        service.delete(driver.getId(), id);
        assertThat(service.list(driver.getId())).isEmpty();
    }

    @Test
    void aVehicleInAnActiveReservationCannotBeDeleted() {
        User owner = persistUser(Role.OWNER);
        ParkingSpot spot = persistSpot(owner, 51.2465, 22.5684, 1000);
        Vehicle vehicle = persistVehicle(driver);
        Reservation reservation = persistReservation(spot, driver, vehicle,
                Instant.parse("2030-01-07T10:00:00Z"), Instant.parse("2030-01-07T12:00:00Z"));
        reservation.markPaid(true);
        em.flush();

        assertThatThrownBy(() -> service.delete(driver.getId(), vehicle.getId()))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("VEHICLE_IN_ACTIVE_RESERVATION"));

        reservation.cancel("changed plans", Instant.parse("2029-12-01T00:00:00Z"));
        em.flush();
        assertThatThrownBy(() -> service.delete(driver.getId(), vehicle.getId()))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("VEHICLE_HAS_RESERVATION_HISTORY"));
    }
}
