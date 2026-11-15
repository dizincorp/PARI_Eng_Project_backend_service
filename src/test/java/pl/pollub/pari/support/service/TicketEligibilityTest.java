package pl.pollub.pari.support.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.ForbiddenException;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.support.ServiceTestBase;
import pl.pollub.pari.support.domain.ReportStatus;
import pl.pollub.pari.support.domain.TicketPriority;
import pl.pollub.pari.support.domain.TicketType;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which reservation states allow which ticket (PARI-93). Documents the rules in docs/support-workflows.md: the owner can
 * report VEHICLE_NOT_LEFT from the reserved end while the session runs or after it was completed; the driver can report
 * CANNOT_LEAVE only during the session; OTHER is always possible for a participant.
 */
@Import({SupportTicketService.class, SupportCommandService.class, ModerationService.class})
class TicketEligibilityTest extends ServiceTestBase {

    private static final Duration HOUR = Duration.ofHours(1);

    @Autowired
    SupportTicketService tickets;
    @Autowired
    SupportCommandService commands;

    User owner;
    User driver;
    User support;
    Vehicle vehicle;
    int spotNo;

    @BeforeEach
    void setUp() {
        owner = persistUser(Role.OWNER);
        driver = persistUser(Role.DRIVER);
        support = persistUser(Role.SUPPORT);
        vehicle = persistVehicle(driver);
        em.flush();
    }

    /** Every reservation gets its own listing and vehicle, so the cases cannot collide on the overlap constraints. */
    private Reservation reservation(Instant start, Instant end) {
        ParkingSpot spot = persistSpot(owner, 51.2465 + 0.001 * (++spotNo), 22.5684, 1000);
        Reservation r = persistReservation(spot, driver, persistVehicle(driver), start, end);
        em.flush();
        return r;
    }

    private Reservation ended() {
        return reservation(NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(1)));
    }

    private Reservation completed(boolean automatically) {
        Reservation r = ended();
        r.markPaid(true);
        r.checkIn(r.getPeriod().start().plusSeconds(60));
        r.checkOut(NOW.minus(Duration.ofSeconds(30)));
        if (automatically) {
            r.markAutoCheckedOut();
        }
        em.flush();
        return r;
    }

    private void ownerReports(Reservation r) {
        tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "The car is still there");
    }

    // ---- VEHICLE_NOT_LEFT: the owner

    @Test
    void theOwnerCanReportAVehicleAfterTheSessionWasCompletedByTheDriver() {
        Reservation r = completed(false);

        TicketView view = tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "The car is still there");

        assertThat(view.type()).isEqualTo(TicketType.VEHICLE_NOT_LEFT);
        assertThat(view.priority()).isEqualTo(TicketPriority.HIGH);
        assertThat(view.status()).isEqualTo(ReportStatus.OPEN);
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
    }

    @Test
    void theOwnerCanReportAVehicleAfterTheAutomaticCheckOut() {
        Reservation r = completed(true);

        assertThat(tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there").status())
                .isEqualTo(ReportStatus.OPEN);
    }

    @Test
    void aReportOnACompletedReservationDoesNotMakeTheSystemOpenASecondOne() {
        Reservation r = completed(true);
        ownerReports(r);

        assertThat(tickets.createSystemTicket(r.getId())).isFalse();
        assertThat(tickets.mine(owner.getId(), 0, 10)).hasSize(1);
    }

    @Test
    void theSystemOpensNoTicketForACompletedReservation() {
        assertThat(tickets.createSystemTicket(completed(true).getId())).isFalse();
    }

    @Test
    void aSecondOpenReportOnACompletedReservationIsRefusedButAClosedOneMayBeFollowedByANewOne() {
        Reservation r = completed(false);
        TicketView first = tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there");

        assertThatThrownBy(() -> ownerReports(r)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("TICKET_ALREADY_OPEN"));

        commands.close(support.getId(), first.id(), "The driver came back");
        assertThat(tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Back again").status())
                .isEqualTo(ReportStatus.OPEN);
    }

    @Test
    void theOwnerCannotReportBeforeTheReservationEndsWhateverItsState() {
        Reservation upcoming = reservation(NOW.plus(HOUR), NOW.plus(HOUR.multipliedBy(2)));
        upcoming.markPaid(true);
        em.flush();

        assertThatThrownBy(() -> ownerReports(upcoming)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("TICKET_TOO_EARLY"));
    }

    @Test
    void theOwnerCannotReportAReservationThatNeverStartedOrWasEndedWithoutASession() {
        Reservation confirmed = ended();
        confirmed.markPaid(true);
        Reservation noShow = ended();
        noShow.markPaid(true);
        noShow.markNoShow(NOW);
        Reservation cancelled = ended();
        cancelled.markPaid(true);
        cancelled.cancel("Changed plans", NOW.minus(Duration.ofHours(4)));
        Reservation rejected = ended();
        rejected.markPaid(false);
        rejected.reject("Not available", NOW.minus(Duration.ofHours(4)));
        Reservation expired = ended();
        expired.markPaid(false);
        expired.expire(NOW.minus(Duration.ofHours(4)));
        Reservation unpaid = ended();
        em.flush();

        for (Reservation r : new Reservation[]{confirmed, noShow, cancelled, rejected, expired, unpaid}) {
            assertThatThrownBy(() -> ownerReports(r)).as(r.getStatus().name())
                    .isInstanceOfSatisfying(ConflictException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo("SESSION_NOT_RUNNING");
                        assertThat(e.getMessage()).isEqualTo("The parking session is not running");
                    });
        }
    }

    @Test
    void onlyTheOwnerOfTheListingCanReportAVehicleThatDidNotLeave() {
        Reservation r = completed(false);

        assertThatThrownBy(() -> tickets.create(driver.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "x"))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- CANNOT_LEAVE and OTHER

    @Test
    void theDriverCanSayTheyCannotLeaveOnlyWhileTheSessionRuns() {
        Reservation r = completed(false);

        assertThatThrownBy(() -> tickets.create(driver.getId(), r.getId(), TicketType.CANNOT_LEAVE, "The gate is closed"))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("SESSION_NOT_RUNNING"));
        assertThatThrownBy(() -> tickets.create(owner.getId(), r.getId(), TicketType.CANNOT_LEAVE, "x"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void anOtherTicketIsPossibleForBothParticipantsInEveryState() {
        Reservation cancelled = ended();
        cancelled.markPaid(true);
        cancelled.cancel("Changed plans", NOW.minus(Duration.ofHours(4)));
        Reservation upcoming = reservation(NOW.plus(HOUR), NOW.plus(HOUR.multipliedBy(2)));
        em.flush();

        for (Reservation r : new Reservation[]{cancelled, upcoming, completed(true)}) {
            assertThat(tickets.create(driver.getId(), r.getId(), TicketType.OTHER, "A question").type())
                    .isEqualTo(TicketType.OTHER);
            assertThat(tickets.create(owner.getId(), r.getId(), TicketType.OTHER, "A question").type())
                    .isEqualTo(TicketType.OTHER);
        }
    }

    @Test
    void aTicketNeedsADescription() {
        Reservation r = completed(false);

        assertThatThrownBy(() -> tickets.create(owner.getId(), r.getId(), TicketType.OTHER, " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("A description is required");
    }
}
