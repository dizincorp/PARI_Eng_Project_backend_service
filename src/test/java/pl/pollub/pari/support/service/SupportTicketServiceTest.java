package pl.pollub.pari.support.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import pl.pollub.pari.common.domain.IllegalStateTransitionException;
import pl.pollub.pari.common.exception.BadRequestException;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.ForbiddenException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.support.ServiceTestBase;
import pl.pollub.pari.support.domain.EvacuationStatus;
import pl.pollub.pari.support.domain.ReportStatus;
import pl.pollub.pari.support.domain.SanctionType;
import pl.pollub.pari.support.domain.TicketPriority;
import pl.pollub.pari.support.domain.TicketSource;
import pl.pollub.pari.support.domain.TicketType;
import pl.pollub.pari.support.event.TicketEvent;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tickets and support commands on the real schema; the clock is fixed at NOW. */
@Import({SupportTicketService.class, SupportCommandService.class, ModerationService.class})
@RecordApplicationEvents
class SupportTicketServiceTest extends ServiceTestBase {

    @Autowired
    SupportTicketService tickets;
    @Autowired
    SupportCommandService commands;
    @Autowired
    ModerationService moderation;
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

    @Test
    void theStaffDirectoryListsActiveSupportAndAdminAccountsOnly() {
        User admin = persistUser(Role.ADMIN);
        User blockedSupport = persistUser(Role.SUPPORT);
        blockedSupport.block();
        em.flush();

        List<StaffView> staff = tickets.staff(support.getId());

        assertThat(staff).extracting(StaffView::id).contains(support.getId(), admin.getId())
                .doesNotContain(driver.getId(), owner.getId(), blockedSupport.getId());
        assertThat(staff).allSatisfy(s -> assertThat(s.roles()).isNotEmpty());
        assertThatThrownBy(() -> tickets.staff(driver.getId())).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> tickets.staff(owner.getId())).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aReleasedTicketGoesBackToTheQueueStaysOpenAndCanBeClaimedAgain() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(20)));
        TicketView view = tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there");
        User other = persistUser(Role.SUPPORT);
        em.flush();
        tickets.claim(support.getId(), view.id());

        TicketView released = commands.release(support.getId(), view.id(), "Going off shift");

        assertThat(released.assignedToId()).isNull();
        assertThat(released.status()).isEqualTo(ReportStatus.OPEN);
        assertThat(released.resolution()).isNull();
        assertThat(tickets.queue(support.getId(), ReportStatus.OPEN, null, null, true, 0, 10)).extracting(TicketView::id)
                .contains(view.id());
        assertThat(tickets.claim(other.getId(), view.id()).assignedToId()).isEqualTo(other.getId());
    }

    @Test
    void releaseNeedsAReasonAnAssignedOpenTicketAndTheSupportRole() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(20)));
        TicketView view = tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there");

        assertThatThrownBy(() -> commands.release(support.getId(), view.id(), "Not assigned yet"))
                .isInstanceOf(IllegalStateTransitionException.class);
        tickets.claim(support.getId(), view.id());
        assertThatThrownBy(() -> commands.release(support.getId(), view.id(), " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> commands.release(driver.getId(), view.id(), "x")).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void theAuditTrailListsEverySuccessfulCommandOldestFirstWithActorReasonAndOutcome() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(10)));
        spot.occupy();
        TicketView view = tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there");
        User other = persistUser(Role.SUPPORT);
        em.flush();
        tickets.claim(support.getId(), view.id());
        commands.reassign(support.getId(), view.id(), other.getId(), "Other agent knows the owner");
        commands.release(other.getId(), view.id(), "Back to the queue");
        commands.forceCheckOut(support.getId(), view.id(), "Driver confirmed by phone");
        assertThatThrownBy(() -> commands.forceCheckOut(support.getId(), view.id(), "Again")).isInstanceOf(RuntimeException.class);

        AuditEventView.Page page = tickets.auditTrail(support.getId(), view.id(), 0, 50);

        assertThat(page.items()).extracting(AuditEventView::action).containsExactly("TICKET_CREATED", "TICKET_CLAIMED",
                "SUPPORT_REASSIGN", "SUPPORT_RELEASE", "SUPPORT_FORCE_CHECK_OUT");
        AuditEventView reassign = page.items().get(2);
        assertThat(reassign.actorId()).isEqualTo(support.getId());
        assertThat(reassign.actorName()).isNotBlank();
        assertThat(reassign.reason()).isEqualTo("Other agent knows the owner");
        assertThat(reassign.outcome()).isEqualTo("SUCCEEDED");
        assertThat(reassign.metadata()).containsEntry("assignedToId", other.getId().toString())
                .containsEntry("previousAssigneeId", support.getId().toString()).doesNotContainKey("reason");
        assertThat(page.total()).isEqualTo(5);
    }

    @Test
    void theAuditTrailIsPaginatedAndForSupportOnly() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(20)));
        TicketView view = tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there");
        tickets.claim(support.getId(), view.id());

        AuditEventView.Page first = tickets.auditTrail(support.getId(), view.id(), 0, 1);
        AuditEventView.Page second = tickets.auditTrail(support.getId(), view.id(), 1, 1);

        assertThat(first.items()).extracting(AuditEventView::action).containsExactly("TICKET_CREATED");
        assertThat(second.items()).extracting(AuditEventView::action).containsExactly("TICKET_CLAIMED");
        assertThat(first.total()).isEqualTo(2);
        assertThatThrownBy(() -> tickets.auditTrail(owner.getId(), view.id(), 0, 10)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> tickets.auditTrail(support.getId(), java.util.UUID.randomUUID(), 0, 10))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aSanctionTargetMustBeAParticipantOfTheTicketsReservation() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(20)));
        TicketView view = tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there");
        User stranger = persistUser(Role.DRIVER);
        em.flush();

        assertThatThrownBy(() -> commands.sanction(support.getId(), view.id(), stranger.getId(), SanctionType.WARNING, "x"))
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getErrorCode()).isEqualTo("SANCTION_TARGET_NOT_PARTICIPANT"));
        commands.sanction(support.getId(), view.id(), driver.getId(), SanctionType.WARNING, "Overstayed");
        commands.sanction(support.getId(), view.id(), owner.getId(), SanctionType.WARNING, "Reported falsely");

        assertThat(moderation.moderation(support.getId(), stranger.getId()).history()).isEmpty();
        assertThat(moderation.moderation(support.getId(), driver.getId()).history()).singleElement()
                .satisfies(h -> {
                    assertThat(h.reason()).isEqualTo("Overstayed");
                    assertThat(h.issuedById()).isEqualTo(support.getId());
                    assertThat(h.ticketId()).isEqualTo(view.id());
                });
    }

    @Test
    void reachingTheWarningLimitBlocksTheAccountAndLeavesABlockEntry() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(20)));
        TicketView view = tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there");

        commands.sanction(support.getId(), view.id(), driver.getId(), SanctionType.WARNING, "First");
        commands.sanction(support.getId(), view.id(), driver.getId(), SanctionType.WARNING, "Second");
        assertThat(moderation.moderation(support.getId(), driver.getId()).blocked()).isFalse();
        commands.sanction(support.getId(), view.id(), driver.getId(), SanctionType.WARNING, "Third");

        assertThat(tickets.auditTrail(support.getId(), view.id(), 0, 50).items()).filteredOn(e -> e.action().equals("SUPPORT_SANCTION_WARNING"))
                .extracting(e -> e.metadata().get("autoBlocked")).containsExactly(false, false, true);
        ModerationService.ModerationView view3 = moderation.moderation(support.getId(), driver.getId());
        assertThat(view3.blocked()).isTrue();
        assertThat(view3.warningCount()).isEqualTo(3);
        assertThat(view3.warningLimit()).isEqualTo(3);
        assertThat(view3.history()).extracting(ModerationService.SanctionView::type)
                .containsExactlyInAnyOrder(SanctionType.WARNING, SanctionType.WARNING, SanctionType.WARNING, SanctionType.BLOCK);
        assertThat(view3.history()).filteredOn(h -> h.type() == SanctionType.BLOCK).singleElement()
                .satisfies(h -> assertThat(h.reason()).isEqualTo("Warning limit reached"));
    }

    @Test
    void anUnblockKeepsTheHistoryResetsTheWarningCountAndAWarningDoesNotBlockAgainAtOnce() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(20)));
        TicketView view = tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there");
        commands.sanction(support.getId(), view.id(), driver.getId(), SanctionType.BLOCK, "Refused to leave");

        ModerationService.ModerationView after = moderation.unblock(support.getId(), driver.getId(), "Apologised");

        assertThat(after.blocked()).isFalse();
        assertThat(after.warningCount()).isZero();
        assertThat(after.history()).extracting(ModerationService.SanctionView::type)
                .containsExactlyInAnyOrder(SanctionType.BLOCK, SanctionType.UNBLOCK);
        assertThat(after.history()).filteredOn(h -> h.type() == SanctionType.UNBLOCK).singleElement()
                .satisfies(h -> assertThat(h.ticketId()).isNull());

        commands.sanction(support.getId(), view.id(), driver.getId(), SanctionType.WARNING, "New warning");
        assertThat(moderation.moderation(support.getId(), driver.getId()).blocked()).isFalse();
        assertThat(moderation.moderation(support.getId(), driver.getId()).warningCount()).isEqualTo(1);
    }

    @Test
    void blockingABlockedAccountOrUnblockingAnActiveOneIsRefused() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(20)));
        TicketView view = tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there");

        assertThatThrownBy(() -> moderation.unblock(support.getId(), driver.getId(), "Not blocked"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("NOT_BLOCKED"));
        commands.sanction(support.getId(), view.id(), driver.getId(), SanctionType.BLOCK, "Refused to leave");
        assertThatThrownBy(() -> commands.sanction(support.getId(), view.id(), driver.getId(), SanctionType.BLOCK, "Again"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("ALREADY_BLOCKED"));
    }

    @Test
    void theBlockedUsersListShowsTheReasonAndDisappearsAfterAnUnblock() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(20)));
        TicketView view = tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there");
        commands.sanction(support.getId(), view.id(), driver.getId(), SanctionType.BLOCK, "Refused to leave");

        assertThat(moderation.blockedUsers(support.getId(), 0, 20)).singleElement().satisfies(b -> {
            assertThat(b.userId()).isEqualTo(driver.getId());
            assertThat(b.reason()).isEqualTo("Refused to leave");
            assertThat(b.blockedSince()).isNotNull();
        });
        moderation.unblock(support.getId(), driver.getId(), "Resolved");
        assertThat(moderation.blockedUsers(support.getId(), 0, 20)).isEmpty();
    }

    @Test
    void moderationLookupAndUnblockAreForSupportOnlyAndUnblockNeedsAReason() {
        assertThatThrownBy(() -> moderation.moderation(driver.getId(), owner.getId())).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> moderation.blockedUsers(owner.getId(), 0, 10)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> moderation.unblock(driver.getId(), owner.getId(), "x")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> moderation.unblock(support.getId(), owner.getId(), " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> moderation.moderation(support.getId(), java.util.UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void supportSeesTheReservationOfATicketWithBothPartiesIdentifiedAndNoAccessSecrets() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(20)));
        TicketView view = tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there"); // reporter: the owner

        SupportReservationView context = tickets.reservationOf(support.getId(), view.id());

        assertThat(context.reservationId()).isEqualTo(r.getId());
        assertThat(context.code()).isEqualTo(r.getCode());
        assertThat(context.status()).isEqualTo(ReservationStatus.IN_PROGRESS);
        assertThat(context.driver().id()).isEqualTo(driver.getId());
        assertThat(context.owner().id()).isEqualTo(owner.getId());
        assertThat(context.driver().email()).isEqualTo(driver.getEmail());
        assertThat(context.spot().id()).isEqualTo(spot.getId());
        assertThat(context.spot().city()).isNotBlank();
        assertThat(context.vehiclePlate()).isEqualTo(vehicle.getPlateNumber());
        assertThat(context.timezone()).isEqualTo("Europe/Warsaw");
        assertThat(context.startsAt()).isEqualTo(r.getPeriod().start());
        assertThat(context.endsAt()).isEqualTo(r.getPeriod().end());
        assertThat(context.lifecycle().checkInAt()).isEqualTo(r.getCheckInAt());
        assertThat(context.lifecycle().total()).isEqualByComparingTo(r.getTotal().toDecimal());
        assertThat(context.refundState()).isEqualTo("NONE");
        assertThat(r.getPinCode()).isNotBlank();
        assertThat(context.toString()).doesNotContain(r.getPinCode()).doesNotContain(r.getQrToken());
    }

    @Test
    void theSupportContextShowsPaymentsAndTheRefundState() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(20)));
        TicketView view = tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there");
        pl.pollub.pari.payment.domain.Payment refund = new pl.pollub.pari.payment.domain.Payment(r,
                pl.pollub.pari.payment.domain.PaymentType.REFUND, pl.pollub.pari.common.domain.Money.pln(500), "re_test_1");
        refund.markSucceeded();
        em.persist(refund);
        em.flush();

        SupportReservationView context = tickets.reservationOf(support.getId(), view.id());

        assertThat(context.refundState()).isEqualTo("REFUNDED");
        assertThat(context.payments()).singleElement().satisfies(pl -> assertThat(pl.amount()).isEqualByComparingTo("5.00"));
    }

    @Test
    void theSupportContextIsForSupportOnlyAndAnUnknownTicketIsNotFound() {
        Reservation r = running(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(20)));
        TicketView view = tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there");

        assertThatThrownBy(() -> tickets.reservationOf(owner.getId(), view.id())).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> tickets.reservationOf(driver.getId(), view.id())).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> tickets.reservationOf(support.getId(), java.util.UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    private Reservation running(Instant start, Instant end) {
        Reservation r = persistReservation(spot, driver, vehicle, start, end);
        r.markPaid(true);
        r.checkIn(start.plusSeconds(60));
        em.flush();
        return r;
    }
}
