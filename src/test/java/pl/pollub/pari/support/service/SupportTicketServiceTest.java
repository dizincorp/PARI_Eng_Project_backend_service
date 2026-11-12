package pl.pollub.pari.support.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.ForbiddenException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.support.ServiceTestBase;
import pl.pollub.pari.support.domain.EvacuationStatus;
import pl.pollub.pari.support.domain.ReportStatus;
import pl.pollub.pari.support.domain.TicketPriority;
import pl.pollub.pari.support.domain.TicketSource;
import pl.pollub.pari.support.domain.TicketType;
import pl.pollub.pari.support.event.TicketEvent;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tickets and support commands on the real schema; the clock is fixed at NOW. */
@Import({SupportTicketService.class, SupportCommandService.class})
@RecordApplicationEvents
class SupportTicketServiceTest extends ServiceTestBase {

    @Autowired
    SupportTicketService tickets;
    @Autowired
    SupportCommandService commands;
    @Autowired
    ApplicationEvents events;

    User owner;
    User driver;
    User support;
    Vehicle vehicle;
    ParkingSpot spot;

    @BeforeEach
    void setUp() {
        owner = persistUser(Role.OWNER);
        driver = persistUser(Role.DRIVER);
        support = persistUser(Role.SUPPORT);
        vehicle = persistVehicle(driver);
        spot = persistSpot(owner, 51.2465, 22.5684, 1000);
        em.flush();
    }

    @Test
    void theOwnerCannotReportAVehicleBeforeTheReservationEnds() {
        Reservation r = running(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)));

        assertThatThrownBy(() -> tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("TICKET_TOO_EARLY"));
    }

    @Test
    void theOwnerReportsAVehicleAfterTheEndAsAHighPriorityTicketAndIsNotifiedOfIt() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(1)));

        TicketView view = tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there");

        assertThat(view.source()).isEqualTo(TicketSource.OWNER);
        assertThat(view.priority()).isEqualTo(TicketPriority.HIGH);
        assertThat(view.status()).isEqualTo(ReportStatus.OPEN);
        assertThat(events.stream(TicketEvent.class)).singleElement()
                .satisfies(e -> assertThat(e.type()).isEqualTo(TicketEvent.Type.CREATED));
    }

    @Test
    void onlyOneVehicleNotLeftTicketCanBeOpenPerReservation() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(1)));
        tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there");

        assertThatThrownBy(() -> tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Again"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("TICKET_ALREADY_OPEN"));
        assertThat(tickets.createSystemTicket(r.getId())).isFalse();
    }

    @Test
    void theDriverCannotReportAVehicleThatDidNotLeaveButCanSayTheyCannotLeave() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(1)));

        assertThatThrownBy(() -> tickets.create(driver.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "x"))
                .isInstanceOf(ForbiddenException.class);
        TicketView view = tickets.create(driver.getId(), r.getId(), TicketType.CANNOT_LEAVE, "The gate is closed");

        assertThat(view.source()).isEqualTo(TicketSource.DRIVER);
        assertThat(view.priority()).isEqualTo(TicketPriority.HIGH);
    }

    @Test
    void aStrangerCannotOpenOrReadATicket() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(1)));
        User stranger = persistUser(Role.DRIVER);
        em.flush();

        assertThatThrownBy(() -> tickets.create(stranger.getId(), r.getId(), TicketType.OTHER, "x")).isInstanceOf(NotFoundException.class);
        TicketView view = tickets.create(owner.getId(), r.getId(), TicketType.OTHER, "A question");
        assertThatThrownBy(() -> tickets.get(stranger.getId(), view.id())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theSystemOpensOneTicketAndDoesNotReopenAfterItIsClosed() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(20)));

        assertThat(tickets.createSystemTicket(r.getId())).isTrue();
        assertThat(tickets.createSystemTicket(r.getId())).isFalse();
        TicketView view = tickets.mine(owner.getId(), 0, 10).get(0);
        assertThat(view.source()).isEqualTo(TicketSource.SYSTEM);
        assertThat(view.reporterId()).isNull();

        commands.close(support.getId(), view.id(), "Driver left");
        assertThat(tickets.createSystemTicket(r.getId())).isFalse();
    }

    @Test
    void theQueueListsHighPriorityFirstAndIsForSupportOnly() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(20)));
        tickets.create(driver.getId(), r.getId(), TicketType.OTHER, "A question");
        tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there");

        assertThat(tickets.queue(support.getId(), null, null, null, false, 0, 10)).extracting(TicketView::type)
                .containsExactly(TicketType.VEHICLE_NOT_LEFT, TicketType.OTHER);
        User admin = persistUser(Role.ADMIN);
        em.flush();
        assertThat(tickets.queue(admin.getId(), null, null, null, false, 0, 10)).hasSize(2);
        assertThatThrownBy(() -> tickets.queue(driver.getId(), null, null, null, false, 0, 10)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> tickets.queue(owner.getId(), null, null, null, false, 0, 10)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aTicketCanBeClaimedByOneSupportEmployeeOnly() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(20)));
        TicketView view = tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there");
        User other = persistUser(Role.SUPPORT);
        em.flush();

        TicketView claimed = tickets.claim(support.getId(), view.id());

        assertThat(claimed.assignedToId()).isEqualTo(support.getId());
        assertThat(claimed.status()).isEqualTo(ReportStatus.IN_PROGRESS);
        assertThatThrownBy(() -> tickets.claim(other.getId(), view.id()))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("TICKET_ALREADY_CLAIMED"));
    }

    @Test
    void internalNotesAreHiddenFromParticipants() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(20)));
        TicketView view = tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there");
        tickets.addMessage(support.getId(), view.id(), "Calling the driver", true);
        tickets.addMessage(support.getId(), view.id(), "We are on it", false);

        assertThat(tickets.get(owner.getId(), view.id()).messages()).extracting(TicketView.MessageView::body)
                .containsExactly("We are on it");
        assertThat(tickets.get(support.getId(), view.id()).messages()).hasSize(2);
        assertThatThrownBy(() -> tickets.addMessage(owner.getId(), view.id(), "secret", true)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void everySupportCommandNeedsTheRoleAndAReason() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(20)));
        TicketView view = tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there");

        assertThatThrownBy(() -> commands.forceCheckOut(driver.getId(), view.id(), "because")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> commands.forceCheckOut(support.getId(), view.id(), " ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.IN_PROGRESS);
    }

    @Test
    void forceCheckOutEndsTheSessionAndAClosedTicketResolvesIt() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(10)));
        spot.occupy();
        TicketView view = tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there");

        commands.forceCheckOut(support.getId(), view.id(), "Driver confirmed by phone");
        TicketView closed = commands.close(support.getId(), view.id(), "Driver left, session ended");

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(r.isAutoCheckedOut()).isFalse();
        assertThat(closed.status()).isEqualTo(ReportStatus.CLOSED);
        assertThat(closed.resolution()).isEqualTo("Driver left, session ended");
    }

    @Test
    void evacuationFollowsItsStatusFlowAndNotifiesTheDriverAndTheOwner() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(10)));
        TicketView view = tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there");

        TicketView requested = commands.requestEvacuation(support.getId(), view.id(), "Public car park, Lublin", "No answer from the driver");
        assertThat(requested.evacuationStatus()).isEqualTo(EvacuationStatus.REQUESTED);
        assertThat(requested.evacuationDestination()).isEqualTo("Public car park, Lublin");

        commands.advanceEvacuation(support.getId(), view.id(), EvacuationStatus.IN_PROGRESS, "Tow truck on the way");
        TicketView done = commands.advanceEvacuation(support.getId(), view.id(), EvacuationStatus.DONE, "Car moved");

        assertThat(done.evacuationStatus()).isEqualTo(EvacuationStatus.DONE);
        assertThat(events.stream(TicketEvent.class).map(TicketEvent::type).toList())
                .contains(TicketEvent.Type.EVACUATION_REQUESTED, TicketEvent.Type.EVACUATION_UPDATED);
    }

    @Test
    void aTicketCannotBeClosedWhileAPenaltyIsHeldAndNoCommandRunsOnAClosedTicket() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(10)));
        TicketView view = tickets.create(driver.getId(), r.getId(), TicketType.CANNOT_LEAVE, "The gate is closed");
        r.applyOverstayPenalty(pl.pollub.pari.common.domain.Money.pln(500));
        r.checkOut(NOW);
        r.holdPenalty();

        assertThatThrownBy(() -> commands.close(support.getId(), view.id(), "Done"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("PENALTY_PENDING"));
        commands.settlePenalty(support.getId(), view.id(), true, "The gate really was closed");
        commands.close(support.getId(), view.id(), "Penalty waived");

        assertThat(r.getFinalCost()).isEqualTo(r.getSpotCost());
        assertThatThrownBy(() -> commands.close(support.getId(), view.id(), "Again"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("TICKET_CLOSED"));
    }

    private Reservation running(Instant start, Instant end) {
        Reservation r = persistReservation(spot, driver, vehicle, start, end);
        r.markPaid(true);
        r.checkIn(start.plusSeconds(60));
        em.flush();
        return r;
    }
}
