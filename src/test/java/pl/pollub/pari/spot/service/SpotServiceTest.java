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
