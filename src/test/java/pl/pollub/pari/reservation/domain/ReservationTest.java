package pl.pollub.pari.reservation.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.pollub.pari.common.domain.IllegalStateTransitionException;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.domain.TimeRange;
import pl.pollub.pari.spot.domain.Address;
import pl.pollub.pari.spot.domain.Dimensions;
import pl.pollub.pari.spot.domain.GeoPoint;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationTest {

    private static final Instant START = Instant.parse("2030-01-07T10:00:00Z");
    private static final Instant END = Instant.parse("2030-01-07T12:00:00Z");

    private Reservation reservation;

    @BeforeEach
    void setUp() {
        User owner = new User("owner@example.com", "hash", "Olga", "Owner", null, Set.of(Role.OWNER));
        User driver = new User("driver@example.com", "hash", "Dan", "Driver", null, Set.of(Role.DRIVER));
        ParkingSpot spot = new ParkingSpot(owner, "Garage", null, new Address("Krakowskie 1", "Lublin", "20-001", "PL"),
                new GeoPoint(51.2465, 22.5684), new Dimensions(500, 250, 200), Money.pln(1000));
        Vehicle vehicle = new Vehicle(driver, "Skoda Octavia", "lu 12345");
        reservation = new Reservation("RSV-TEST-1", spot, driver, vehicle, new TimeRange(START, END),
                Money.pln(1000), Money.pln(2000), Money.pln(100), Money.pln(200));
    }

    @Test
    void startsUnpaidAndHoldsTheSlot() {
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
        assertThat(reservation.getTotal()).isEqualTo(Money.pln(2100));
        assertThat(reservation.holdsSlot()).isTrue();
        assertThat(reservation.getPinCode()).isNull();
    }

    @Test
    void autoAcceptConfirmsAndIssuesPinAndQr() {
        reservation.markPaid(true);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getPinCode()).matches("\\d{4}");
        assertThat(reservation.getQrToken()).isNotBlank();
    }

    @Test
    void ownerApprovalFlow() {
        reservation.markPaid(false);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PENDING_APPROVAL);
        assertThat(reservation.getPinCode()).isNull();

        reservation.accept();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getPinCode()).isNotNull();
    }

    @Test
    void rejectedReservationFreesTheSlot() {
        reservation.markPaid(false);

        reservation.reject("Not available that day", START.minusSeconds(3600));

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(reservation.holdsSlot()).isFalse();
        assertThat(reservation.getCancelReason()).isEqualTo("Not available that day");
    }

    @Test
    void checkInOnlyInsideTheReservedPeriod() {
        reservation.markPaid(true);

        assertThatThrownBy(() -> reservation.checkIn(START.minusSeconds(5 * 60 + 1))).isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(() -> reservation.checkIn(END)).isInstanceOf(IllegalStateTransitionException.class);

        reservation.checkIn(START.plusSeconds(60));
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.IN_PROGRESS);
        assertThat(reservation.getCheckInAt()).isEqualTo(START.plusSeconds(60));
    }

    @Test
    void checkInOpensFiveMinutesBeforeTheStartAndKeepsTheBookedInterval() {
        reservation.markPaid(true);
        Instant early = START.minusSeconds(5 * 60);

        assertThat(reservation.earliestCheckIn()).isEqualTo(early);
        reservation.checkIn(early);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.IN_PROGRESS);
        assertThat(reservation.getCheckInAt()).isEqualTo(early);
        assertThat(reservation.getPeriod().start()).isEqualTo(START);
        assertThat(reservation.getPeriod().end()).isEqualTo(END);
    }

    @Test
    void aPendingRequestCanExpireAndThenNoLongerHoldsTheSlot() {
        reservation.markPaid(false);

        reservation.expire(START.minusSeconds(60));

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservation.getCancelledAt()).isEqualTo(START.minusSeconds(60));
        assertThat(reservation.holdsSlot()).isFalse();
    }

    @Test
    void onlyAPendingApprovalCanExpire() {
        reservation.markPaid(true);

        assertThatThrownBy(() -> reservation.expire(START)).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void overstayMinutesRoundUpAndAreZeroWhenOnTime() {
        reservation.markPaid(true);
        reservation.checkIn(START);
        reservation.checkOut(END.plusSeconds(61));

        assertThat(reservation.overstayMinutes()).isEqualTo(2);
    }

    @Test
    void checkOutWithoutOverstayCostsTheSpotCost() {
        reservation.markPaid(true);
        reservation.checkIn(START);

        reservation.checkOut(END.minusSeconds(60));

        assertThat(reservation.overstayMinutes()).isZero();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(reservation.getFinalCost()).isEqualTo(Money.pln(2000));
        assertThat(reservation.holdsSlot()).isFalse();
    }

    @Test
    void overstayPenaltyIsAddedToTheFinalCost() {
        reservation.markPaid(true);
        reservation.checkIn(START);
        assertThat(reservation.isOverstaying(END.plusSeconds(1))).isTrue();

        reservation.applyOverstayPenalty(Money.pln(750));
        reservation.checkOut(END.plusSeconds(1800));

        assertThat(reservation.getFinalCost()).isEqualTo(Money.pln(2750));
    }

    @Test
    void cancelIsPossibleBeforeCheckInOnly() {
        reservation.markPaid(true);
        reservation.cancel("Changed plans", START.minusSeconds(7200));
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.holdsSlot()).isFalse();

        assertThatThrownBy(reservation::accept).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void cannotCancelAfterCheckIn() {
        reservation.markPaid(true);
        reservation.checkIn(START);

        assertThatThrownBy(() -> reservation.cancel("too late", START.plusSeconds(60)))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void invalidTransitionsAreRejected() {
        assertThatThrownBy(reservation::accept).isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(() -> reservation.checkIn(START)).isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(() -> reservation.checkOut(END)).isInstanceOf(IllegalStateTransitionException.class);
        reservation.markPaid(true);
        assertThatThrownBy(() -> reservation.markPaid(true)).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void slotHoldingStatusesMatchTheDatabaseConstraint() {
        assertThat(ReservationStatus.SLOT_HOLDING).containsExactlyInAnyOrder(
                ReservationStatus.PENDING_PAYMENT, ReservationStatus.PENDING_APPROVAL,
                ReservationStatus.CONFIRMED, ReservationStatus.IN_PROGRESS);
    }

    @Test
    void theBufferBlocksTheSpotAfterTheEndButNeverShortensIt() {
        assertThat(reservation.getBlockedUntil()).isEqualTo(END);

        reservation.applyBuffer(java.time.Duration.ofMinutes(60));
        assertThat(reservation.getBlockedUntil()).isEqualTo(END.plusSeconds(3600));

        reservation.extendBlockUntil(END.plusSeconds(1800));
        assertThat(reservation.getBlockedUntil()).isEqualTo(END.plusSeconds(3600));
        reservation.extendBlockUntil(END.plusSeconds(7200));
        assertThat(reservation.getBlockedUntil()).isEqualTo(END.plusSeconds(7200));
    }

    @Test
    void aConfirmedReservationBecomesNoShowOnlyAfterTheEnd() {
        reservation.markPaid(true);

        assertThatThrownBy(() -> reservation.markNoShow(END.minusSeconds(1))).isInstanceOf(IllegalStateTransitionException.class);
        reservation.markNoShow(END);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
        assertThat(reservation.holdsSlot()).isFalse();
    }

    @Test
    void aSessionInProgressCannotBecomeNoShow() {
        reservation.markPaid(true);
        reservation.checkIn(START);

        assertThatThrownBy(() -> reservation.markNoShow(END.plusSeconds(60))).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void checkInIsRejectedAfterTheEnd() {
        reservation.markPaid(true);

        assertThatThrownBy(() -> reservation.checkIn(END)).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void autoCheckOutKeepsStatusCompletedAndMarksIt() {
        reservation.markPaid(true);
        reservation.checkIn(START);
        reservation.checkOut(END.plusSeconds(3600));
        reservation.markAutoCheckedOut();

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(reservation.isAutoCheckedOut()).isTrue();
    }

    @Test
    void theAutoCheckedOutMarkNeedsACompletedReservation() {
        reservation.markPaid(true);

        assertThatThrownBy(reservation::markAutoCheckedOut).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void aHeldPenaltyCanBeWaivedWhichRestoresTheFinalCost() {
        reservation.markPaid(true);
        reservation.checkIn(START);
        reservation.applyOverstayPenalty(Money.pln(500));
        reservation.checkOut(END.plusSeconds(1800));
        reservation.holdPenalty();
        assertThat(reservation.getFinalCost()).isEqualTo(Money.pln(2500));

        reservation.settlePenalty(true);

        assertThat(reservation.isPenaltyPending()).isFalse();
        assertThat(reservation.getOverstayPenalty()).isEqualTo(Money.ZERO);
        assertThat(reservation.getFinalCost()).isEqualTo(Money.pln(2000));
    }

    @Test
    void aPenaltyThatIsNotHeldCannotBeSettled() {
        assertThatThrownBy(() -> reservation.settlePenalty(true)).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void aWrongNoShowCanBeCorrectedIntoACancellation() {
        reservation.markPaid(true);
        reservation.markNoShow(END);

        reservation.correctNoShow("The gate was broken", END.plusSeconds(60));

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelReason()).isEqualTo("The gate was broken");
    }

    @Test
    void eachReminderIsSentOnlyOnce() {
        assertThat(reservation.markReminderSent(Reservation.REMINDER_AT_END)).isTrue();
        assertThat(reservation.markReminderSent(Reservation.REMINDER_AT_END)).isFalse();
        assertThat(reservation.markReminderSent(Reservation.REMINDER_FINAL)).isTrue();
    }
}
