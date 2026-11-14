package pl.pollub.pari.spot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.ForbiddenException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.spot.service.SpotService.CreateSpotCommand;
import pl.pollub.pari.support.ServiceTestBase;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpotUnitServiceTest extends ServiceTestBase {

    @Autowired
    SpotService spots;
    @Autowired
    SpotUnitService units;

    User owner;

    @BeforeEach
    void setUp() {
        owner = persistUser(Role.OWNER);
        em.flush();
    }

    private static CreateSpotCommand command() {
        return new CreateSpotCommand("Garage", "Covered", "ul. Krakowskie 1", "Lublin", "20-002", null,
                51.2465, 22.5684, 500, 250, 200, Money.pln(1250), null, true, Set.of(), null, true, false);
    }

    private static List<String> labels(List<SpotView.UnitRef> refs) {
        return refs.stream().map(SpotView.UnitRef::label).toList();
    }

    private void setLimit(int max) {
        em.createNativeQuery("update platform_settings set setting_value = :v where setting_key = 'max_units_per_spot'")
                .setParameter("v", String.valueOf(max)).executeUpdate();
    }

    @Test
    void aNewListingHasOneSpaceCalledNumberOne() {
        SpotView view = spots.create(owner.getId(), command());

        assertThat(labels(view.units())).containsExactly("#1");
        assertThat(view.units().get(0).id()).isNotNull();
    }

    @Test
    void anOwnerCanCreateOneListingWithSeveralNumberedSpaces() {
        SpotView view = spots.create(owner.getId(), command(), 3);
        em.flush();
        em.clear();

        assertThat(labels(spots.get(view.id(), null).units())).containsExactly("#1", "#2", "#3");
        assertThat(spots.get(view.id(), null).units()).extracting(SpotView.UnitRef::id).doesNotHaveDuplicates();
    }

    @Test
    void theNumberOfSpacesMustBeBetweenOneAndTheLimit() {
        assertThatThrownBy(() -> spots.create(owner.getId(), command(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> spots.create(owner.getId(), command(), 51)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void spacesCanBeAddedWithADefaultOrACustomLabelButNotWithATakenOne() {
        UUID id = spots.create(owner.getId(), command()).id();

        assertThat(labels(units.add(owner.getId(), id, null))).containsExactly("#1", "#2");
        assertThat(labels(units.add(owner.getId(), id, " North "))).containsExactly("#1", "#2", "North");
        assertThatThrownBy(() -> units.add(owner.getId(), id, "north"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("SPOT_UNIT_LABEL_TAKEN"));
        assertThatThrownBy(() -> units.add(owner.getId(), id, "x".repeat(21))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theNumberOfSpacesIsLimitedByThePlatformSetting() {
        setLimit(2);
        UUID id = spots.create(owner.getId(), command(), 2).id();

        assertThatThrownBy(() -> units.add(owner.getId(), id, null))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("SPOT_UNIT_LIMIT"));
        assertThatThrownBy(() -> spots.create(owner.getId(), command(), 3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void renamingKeepsTheIdAndRefusesALabelThatIsInUse() {
        UUID id = spots.create(owner.getId(), command(), 2).id();
        List<SpotView.UnitRef> before = spots.get(id, null).units();

        List<SpotView.UnitRef> after = units.rename(owner.getId(), id, before.get(0).id(), "Gate side");

        assertThat(after.get(0).id()).isEqualTo(before.get(0).id());
        assertThat(labels(after)).containsExactly("Gate side", "#2");
        assertThatThrownBy(() -> units.rename(owner.getId(), id, before.get(1).id(), "gate side"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("SPOT_UNIT_LABEL_TAKEN"));
        assertThatThrownBy(() -> units.rename(owner.getId(), id, UUID.randomUUID(), "A")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aSpaceWithoutReservationsCanBeRemovedAndItsLabelReused() {
        UUID id = spots.create(owner.getId(), command(), 2).id();
        UUID second = spots.get(id, null).units().get(1).id();

        List<SpotView.UnitRef> after = units.remove(owner.getId(), id, second);
        em.flush();
        em.clear();

        assertThat(labels(after)).containsExactly("#1");
        assertThat(labels(spots.get(id, null).units())).containsExactly("#1");
        assertThat(labels(units.add(owner.getId(), id, "#2"))).containsExactly("#1", "#2");
        assertThatThrownBy(() -> units.remove(owner.getId(), id, second)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theLastSpaceCannotBeRemoved() {
        SpotView view = spots.create(owner.getId(), command());

        assertThatThrownBy(() -> units.remove(owner.getId(), view.id(), view.units().get(0).id()))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("SPOT_UNIT_LAST"));
    }

    @Test
    void aSpaceWithAnActiveOrUpcomingReservationCannotBeRemoved() {
        SpotView view = spots.create(owner.getId(), command(), 2);
        em.flush();
        em.clear();
        ParkingSpot spot = em.find(ParkingSpot.class, view.id());
        User driver = persistUser(Role.DRIVER);
        Vehicle vehicle = persistVehicle(driver);
        Reservation held = newReservation(spot, driver, vehicle, NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(1)).plusSeconds(3600));
        held.assignUnit(spot.liveUnits().get(1));
        em.persist(held);
        em.flush();

        assertThatThrownBy(() -> units.remove(owner.getId(), view.id(), view.units().get(1).id()))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("SPOT_UNIT_HAS_RESERVATIONS");
                    assertThat(e.getDetails()).containsEntry("label", "#2");
                });
        // the other space is free and can go
        assertThat(labels(units.remove(owner.getId(), view.id(), view.units().get(0).id()))).containsExactly("#2");
    }

    @Test
    void aRemovedSpaceKeepsTheLabelOfItsFinishedReservations() {
        SpotView view = spots.create(owner.getId(), command(), 2);
        em.flush();
        em.clear();
        ParkingSpot spot = em.find(ParkingSpot.class, view.id());
        User driver = persistUser(Role.DRIVER);
        Reservation old = newReservation(spot, driver, persistVehicle(driver), NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofDays(3)).plusSeconds(3600));
        old.assignUnit(spot.liveUnits().get(1));
        em.persist(old);
        em.flush();

        units.remove(owner.getId(), view.id(), view.units().get(1).id());
        em.flush();
        em.clear();

        assertThat(em.find(Reservation.class, old.getId()).getUnitLabel()).isEqualTo("#2");
    }

    @Test
    void onlyTheOwnerOrAnAdminCanChangeSpaces() {
        SpotView view = spots.create(owner.getId(), command(), 2);
        User stranger = persistUser(Role.OWNER);
        User admin = persistUser(Role.ADMIN);
        UUID unit = view.units().get(1).id();

        assertThatThrownBy(() -> units.add(stranger.getId(), view.id(), null)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> units.rename(stranger.getId(), view.id(), unit, "X")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> units.remove(stranger.getId(), view.id(), unit)).isInstanceOf(ForbiddenException.class);
        assertThat(labels(units.add(admin.getId(), view.id(), null))).containsExactly("#1", "#2", "#3");
    }
}
