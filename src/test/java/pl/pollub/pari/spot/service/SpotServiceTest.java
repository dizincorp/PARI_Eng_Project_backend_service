package pl.pollub.pari.spot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.exception.ForbiddenException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.spot.domain.Occupancy;
import pl.pollub.pari.spot.domain.SpotStatus;
import pl.pollub.pari.spot.service.SpotService.CreateSpotCommand;
import pl.pollub.pari.support.ServiceTestBase;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.verification.domain.VerificationStatus;
import pl.pollub.pari.verification.repository.VerificationRequestRepository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpotServiceTest extends ServiceTestBase {

    @Autowired
    SpotService service;
    @Autowired
    VerificationRequestRepository verifications;

    User owner;

    @BeforeEach
    void setUp() {
        owner = persistUser(Role.OWNER);
        em.flush();
    }

    @Test
    void creatingASpaceActivatesItAndKeepsAllTheOwnersInput() {
        SpotView view = service.create(owner.getId(), command(true, false, Set.of("COVERED", "CCTV")));

        assertThat(view.status()).isEqualTo(SpotStatus.ACTIVE);
        assertThat(view.occupancy()).isEqualTo(Occupancy.FREE);
        assertThat(view.verified()).isFalse();
        assertThat(view.ownerId()).isEqualTo(owner.getId());
        assertThat(view.city()).isEqualTo("Lublin");
        assertThat(view.country()).isEqualTo("PL");
        assertThat(view.hourlyPrice()).isEqualByComparingTo("12.50");
        assertThat(view.amenities()).containsExactly("CCTV", "COVERED");
        assertThat(view.photoKeys()).containsExactly("spots/a.jpg", "spots/b.jpg");
        assertThat(view.accessInstructions()).isEqualTo("Gate code 4921");
        assertThat(verifications.findBySpotIdOrderByRequestedAtDesc(view.id())).isEmpty();
    }

    @Test
    void askingForVerificationQueuesAnInspectionRequest() {
        SpotView view = service.create(owner.getId(), command(true, true, Set.of()));

        assertThat(verifications.findBySpotIdOrderByRequestedAtDesc(view.id()))
                .singleElement().satisfies(r -> assertThat(r.getStatus()).isEqualTo(VerificationStatus.REQUESTED));
    }

    @Test
    void theLegalDeclarationIsMandatory() {
        assertThatThrownBy(() -> service.create(owner.getId(), command(false, false, Set.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("declaration");
    }

    @Test
    void onlyActiveOwnersCanListASpace() {
        User driverOnly = persistUser(Role.DRIVER);
        assertThatThrownBy(() -> service.create(driverOnly.getId(), command(true, false, Set.of())))
                .isInstanceOf(ForbiddenException.class);

        owner.block();
        assertThatThrownBy(() -> service.create(owner.getId(), command(true, false, Set.of())))
                .isInstanceOf(ForbiddenException.class);

        assertThatThrownBy(() -> service.create(UUID.randomUUID(), command(true, false, Set.of())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void unknownAmenitiesAndNonPositivePricesAreRejected() {
        assertThatThrownBy(() -> service.create(owner.getId(), command(true, false, Set.of("JACUZZI"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JACUZZI");

        CreateSpotCommand free = withPrice(Money.ZERO);
        assertThatThrownBy(() -> service.create(owner.getId(), free))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price");
    }

    @Test
    void accessInstructionsAreVisibleToTheOwnerOnly() {
        UUID id = service.create(owner.getId(), command(true, false, Set.of())).id();
        UUID someoneElse = persistUser(Role.DRIVER).getId();

        assertThat(service.get(id, owner.getId()).accessInstructions()).isEqualTo("Gate code 4921");
        assertThat(service.get(id, someoneElse).accessInstructions()).isNull();
        assertThat(service.get(id, null).accessInstructions()).isNull();
    }

    @Test
    void aDeactivatedSpaceDisappearsForEveryoneButItsOwnerAndCanBeActivatedAgain() {
        UUID id = service.create(owner.getId(), command(true, false, Set.of())).id();
        UUID someoneElse = persistUser(Role.DRIVER).getId();

        assertThat(service.deactivate(owner.getId(), id).status()).isEqualTo(SpotStatus.INACTIVE);
        assertThatThrownBy(() -> service.get(id, someoneElse)).isInstanceOf(NotFoundException.class);
        assertThat(service.get(id, owner.getId()).status()).isEqualTo(SpotStatus.INACTIVE);

        assertThat(service.activate(owner.getId(), id).status()).isEqualTo(SpotStatus.ACTIVE);
        assertThat(service.get(id, someoneElse).status()).isEqualTo(SpotStatus.ACTIVE);
    }

    @Test
    void onlyTheOwnerCanChangeOrListASpace() {
        UUID id = service.create(owner.getId(), command(true, false, Set.of())).id();
        UUID someoneElse = persistUser(Role.OWNER).getId();

        assertThatThrownBy(() -> service.deactivate(someoneElse, id)).isInstanceOf(NotFoundException.class);
        assertThat(service.listOwn(owner.getId())).extracting(SpotView::id).containsExactly(id);
        assertThat(service.listOwn(someoneElse)).isEmpty();
    }

    @Test
    void theOwnerCanEditTheSpaceAndItsAmenities() {
        UUID id = service.create(owner.getId(), command(true, false, Set.of("COVERED"))).id();

        SpotView updated = service.update(owner.getId(), id, new SpotService.UpdateSpotCommand(
                "Garage 8", "New description", Money.pln(1450), 520, 260, 210, "New gate code 5678", Set.of("CCTV", "GATE")));

        assertThat(updated.name()).isEqualTo("Garage 8");
        assertThat(updated.hourlyPrice()).isEqualByComparingTo("14.50");
        assertThat(updated.lengthCm()).isEqualTo(520);
        assertThat(updated.accessInstructions()).isEqualTo("New gate code 5678");
        assertThat(updated.amenities()).containsExactly("CCTV", "GATE");
        assertThat(updated.status()).isEqualTo(SpotStatus.ACTIVE);
    }

    @Test
    void anOmittedAmenityListKeepsTheCurrentOnes() {
        UUID id = service.create(owner.getId(), command(true, false, Set.of("COVERED"))).id();

        SpotView updated = service.update(owner.getId(), id, new SpotService.UpdateSpotCommand(
                "Garage 7", null, Money.pln(1250), 500, 250, null, null, null));

        assertThat(updated.amenities()).containsExactly("COVERED");
    }

    @Test
    void editingNeedsTheOwnerOrAnAdminAndAPositivePrice() {
        UUID id = service.create(owner.getId(), command(true, false, Set.of())).id();
        User stranger = persistUser(Role.OWNER);
        User admin = persistUser(Role.ADMIN);
        em.flush();
        SpotService.UpdateSpotCommand cmd = new SpotService.UpdateSpotCommand("X", null, Money.pln(1000), 500, 250, null, null, null);

        assertThatThrownBy(() -> service.update(stranger.getId(), id, cmd)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.update(owner.getId(), UUID.randomUUID(), cmd)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.update(owner.getId(), id, new SpotService.UpdateSpotCommand("X", null, Money.pln(0), 500, 250, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.update(admin.getId(), id, cmd).name()).isEqualTo("X");
    }

    @Test
    void deletingArchivesTheSpaceAndRemovesItFromEveryView() {
        UUID id = service.create(owner.getId(), command(true, false, Set.of())).id();

        service.archive(owner.getId(), id);

        assertThat(service.listOwn(owner.getId())).isEmpty();
        assertThatThrownBy(() -> service.get(id, owner.getId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.activate(owner.getId(), id)).isInstanceOf(NotFoundException.class);
        assertThat(em.find(pl.pollub.pari.spot.domain.ParkingSpot.class, id).getStatus()).isEqualTo(SpotStatus.ARCHIVED);
    }

    @Test
    void aSpaceWithALiveReservationCannotBeDeleted() {
        pl.pollub.pari.spot.domain.ParkingSpot spot = persistSpot(owner, 51.2465, 22.5684, 1000);
        User driver = persistUser(Role.DRIVER);
        pl.pollub.pari.reservation.domain.Reservation reservation = persistReservation(spot, driver, persistVehicle(driver),
                java.time.Instant.parse("2030-01-07T10:00:00Z"), java.time.Instant.parse("2030-01-07T12:00:00Z"));
        reservation.markPaid(true);
        em.flush();

        assertThatThrownBy(() -> service.archive(owner.getId(), spot.getId()))
                .isInstanceOfSatisfying(pl.pollub.pari.common.exception.ConflictException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("SPOT_HAS_ACTIVE_RESERVATIONS");
                    assertThat(e.getMessage()).isEqualTo("Cannot delete spot with active or upcoming reservations.");
                });

        reservation.cancel("changed plans", java.time.Instant.parse("2029-12-01T00:00:00Z"));
        em.flush();
        service.archive(owner.getId(), spot.getId());
        assertThat(spot.getStatus()).isEqualTo(SpotStatus.ARCHIVED);
    }

    @Test
    void onlyTheOwnerCanDeleteASpace() {
        UUID id = service.create(owner.getId(), command(true, false, Set.of())).id();

        assertThatThrownBy(() -> service.archive(persistUser(Role.OWNER).getId(), id)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void ownerAndAdminCanAddAndRemovePhotos() {
        UUID id = service.create(owner.getId(), command(true, false, Set.of())).id();
        User admin = persistUser(Role.ADMIN);

        SpotService.PhotoView first = service.addPhoto(owner.getId(), id, "spots/c.jpg", "http://x/media/spots/c.jpg");
        SpotService.PhotoView second = service.addPhoto(admin.getId(), id, "spots/d.jpg", "http://x/media/spots/d.jpg");

        assertThat(first.id()).isNotNull();
        assertThat(second.position()).isEqualTo(3);
        assertThat(service.removePhoto(owner.getId(), id, first.id())).isEqualTo("spots/c.jpg");
        assertThat(service.get(id, owner.getId()).photoKeys()).containsExactly("spots/a.jpg", "spots/b.jpg", "spots/d.jpg");
    }

    @Test
    void strangersCannotChangePhotosAndUnknownPhotosAre404() {
        UUID id = service.create(owner.getId(), command(true, false, Set.of())).id();
        User stranger = persistUser(Role.OWNER);

        assertThatThrownBy(() -> service.addPhoto(stranger.getId(), id, "spots/c.jpg", "u")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.requireManageable(stranger.getId(), id)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.removePhoto(owner.getId(), id, UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }

    private static CreateSpotCommand command(boolean declared, boolean verification, Set<String> amenities) {
        return build(Money.pln(1250), declared, verification, amenities);
    }

    private static CreateSpotCommand withPrice(Money price) {
        return build(price, true, false, Set.of());
    }

    private static CreateSpotCommand build(Money price, boolean declared, boolean verification, Set<String> amenities) {
        return new CreateSpotCommand("Garage 7", "Covered garage", "ul. Krakowskie Przedmiescie 1", "Lublin", "20-002", null,
                51.2465, 22.5684, 500, 250, 200, price, "Gate code 4921", true, amenities,
                List.of("spots/a.jpg", "spots/b.jpg"), declared, verification);
    }
}
