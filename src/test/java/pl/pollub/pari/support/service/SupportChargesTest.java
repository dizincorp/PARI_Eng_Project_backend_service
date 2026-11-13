package pl.pollub.pari.support.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import pl.pollub.pari.common.domain.IllegalStateTransitionException;
import pl.pollub.pari.common.exception.BadRequestException;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.ForbiddenException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.payment.domain.PaymentStatus;
import pl.pollub.pari.payment.domain.PaymentType;
import pl.pollub.pari.payment.gateway.MockPaymentGateway;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.support.ServiceTestBase;
import pl.pollub.pari.support.domain.EvacuationFee;
import pl.pollub.pari.support.domain.EvacuationStatus;
import pl.pollub.pari.support.domain.PenaltyState;
import pl.pollub.pari.support.domain.TicketType;
import pl.pollub.pari.user.domain.PaymentMethod;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PARI-87 (assessed penalties) and PARI-88 (itemized evacuation fees) on the real schema. */
@Import({SupportTicketService.class, SupportCommandService.class, ModerationService.class})
class SupportChargesTest extends ServiceTestBase {

    @Autowired
    SupportTicketService tickets;
    @Autowired
    SupportCommandService commands;

    User owner;
    User driver;
    User support;
    ParkingSpot spot;
    Reservation reservation;
    UUID ticketId;

    @BeforeEach
    void setUp() {
        owner = persistUser(Role.OWNER);
        driver = persistUser(Role.DRIVER);
        support = persistUser(Role.SUPPORT);
        Vehicle vehicle = persistVehicle(driver);
        spot = persistSpot(owner, 51.2465, 22.5684, 1000);
        PaymentMethod card = new PaymentMethod(driver, "pm_card_visa", "visa", "4242", 12, 2030);
        card.markDefault(true);
        em.persist(card);
        reservation = persistReservation(spot, driver, vehicle, NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(20)));
        reservation.markPaid(true);
        reservation.checkIn(NOW.minus(Duration.ofHours(3)).plusSeconds(60));
        em.flush();
        ticketId = tickets.create(owner.getId(), reservation.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there").id();
        em.flush();
    }

    // ---- PARI-87

    @Test
    void assessingAPenaltyRecordsAPendingAmountAndChargesNothing() {
        TicketView view = commands.assessPenalty(support.getId(), ticketId, 2500, null, "Blocked the gate", "op-1");
        em.clear();

        assertThat(view.penalties()).singleElement().satisfies(p -> {
            assertThat(p.state()).isEqualTo(PenaltyState.PENDING);
            assertThat(p.amount()).isEqualByComparingTo("25.00");
            assertThat(p.currency()).isEqualTo("PLN");
            assertThat(p.targetUserId()).isEqualTo(driver.getId());
            assertThat(p.reason()).isEqualTo("Blocked the gate");
        });
        assertThat(view.penaltyTotal()).isEqualByComparingTo("0.00");
        assertThat(paymentsOfType(PaymentType.PENALTY)).isZero();
    }

    @Test
    void aPenaltyNeedsAReasonAPositiveAmountAndAnOperationId() {
        assertThatThrownBy(() -> commands.assessPenalty(support.getId(), ticketId, 2500, null, " ", "op"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> commands.assessPenalty(support.getId(), ticketId, 0, null, "Reason", "op"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> commands.assessPenalty(support.getId(), ticketId, -5, null, "Reason", "op"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> commands.assessPenalty(support.getId(), ticketId, 2500, null, "Reason", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyASupportEmployeeCanAssessAPenalty() {
        assertThatThrownBy(() -> commands.assessPenalty(owner.getId(), ticketId, 2500, null, "Reason", "op"))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> commands.assessPenalty(driver.getId(), ticketId, 2500, null, "Reason", "op"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aPenaltyCanOnlyTargetTheDriverWhoPays() {
        assertThatThrownBy(() -> commands.assessPenalty(support.getId(), ticketId, 2500, owner.getId(), "Reason", "op"))
                .isInstanceOfSatisfying(BadRequestException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("PENALTY_TARGET_NOT_PAYER"));
        assertThat(commands.assessPenalty(support.getId(), ticketId, 2500, driver.getId(), "Reason", "op").penalties()).hasSize(1);
    }

    @Test
    void aReservationThatNeverStartedIsNotEligibleAndNothingIsRecorded() {
        Reservation upcoming = persistReservation(spot, driver, persistVehicle(driver), NOW.plus(Duration.ofDays(2)),
                NOW.plus(Duration.ofDays(2)).plusSeconds(3600));
        upcoming.markPaid(true);
        em.flush();
        UUID other = ticketFor(upcoming);

        assertThatThrownBy(() -> commands.assessPenalty(support.getId(), other, 2500, null, "Reason", "op"))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("PENALTY_RESERVATION_NOT_ELIGIBLE"));
        em.clear();
        assertThat(count("support_penalties")).isZero();
    }

    @Test
    void sendingTheSameOperationAgainCreatesNoSecondPenalty() {
        commands.assessPenalty(support.getId(), ticketId, 2500, null, "Blocked the gate", "op-1");
        em.clear();
        TicketView again = commands.assessPenalty(support.getId(), ticketId, 2500, null, "Blocked the gate", "op-1");

        assertThat(again.penalties()).hasSize(1);
        assertThat(count("support_penalties")).isEqualTo(1);
        assertThat(commandAudits("SUPPORT_ASSESS_PENALTY")).isEqualTo(1);
    }

    @Test
    void chargingAPendingPenaltyCollectsItOnceAndMakesItCharged() {
        UUID id = assess(2500, "op-1");

        TicketView charged = commands.chargePenalty(support.getId(), ticketId, id, "Confirmed with the owner");
        em.clear();

        assertThat(charged.penalties()).singleElement().satisfies(p -> assertThat(p.state()).isEqualTo(PenaltyState.CHARGED));
        assertThat(charged.penaltyTotal()).isEqualByComparingTo("25.00");
        assertThat(paymentsOfType(PaymentType.PENALTY)).isEqualTo(1);
        assertThat(paymentStatuses(PaymentType.PENALTY)).containsExactly(PaymentStatus.SUCCEEDED);

        assertThatThrownBy(() -> commands.chargePenalty(support.getId(), ticketId, id, "Again"))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThat(paymentsOfType(PaymentType.PENALTY)).isEqualTo(1);
    }

    @Test
    void aDeclinedCardLeavesAFailedPenaltyAndAFailedPayment() {
        jdbc.update("update payment_methods set provider_ref = ? where user_id = ?", MockPaymentGateway.DECLINED_TOKEN, driver.getId());
        em.clear();
        UUID id = assess(2500, "op-1");

        TicketView view = commands.chargePenalty(support.getId(), ticketId, id, "Try to collect");
        em.clear();

        assertThat(view.penalties()).singleElement().satisfies(p -> assertThat(p.state()).isEqualTo(PenaltyState.FAILED));
        assertThat(view.penaltyTotal()).isEqualByComparingTo("0.00");
        assertThat(paymentStatuses(PaymentType.PENALTY)).containsExactly(PaymentStatus.FAILED);
        assertThatThrownBy(() -> commands.waiveAssessedPenalty(support.getId(), ticketId, id, "Too late"))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void aPendingPenaltyCanBeWaivedButNotAfterwardsCharged() {
        UUID id = assess(2500, "op-1");

        TicketView waived = commands.waiveAssessedPenalty(support.getId(), ticketId, id, "Goodwill");
        em.clear();

        assertThat(waived.penalties()).singleElement().satisfies(p -> {
            assertThat(p.state()).isEqualTo(PenaltyState.WAIVED);
            assertThat(p.decisionReason()).isEqualTo("Goodwill");
            assertThat(p.decidedAt()).isNotNull();
        });
        assertThatThrownBy(() -> commands.chargePenalty(support.getId(), ticketId, id, "Changed my mind"))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThat(paymentsOfType(PaymentType.PENALTY)).isZero();
    }

    @Test
    void aChargedPenaltyCannotBeWaived() {
        UUID id = assess(2500, "op-1");
        commands.chargePenalty(support.getId(), ticketId, id, "Collect");

        assertThatThrownBy(() -> commands.waiveAssessedPenalty(support.getId(), ticketId, id, "Too late"))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void aPenaltyOfAnotherTicketIsNotFoundAndTheTicketCannotBeClosedWhileOneIsPending() {
        UUID id = assess(2500, "op-1");
        Reservation second = persistReservation(spot, driver, persistVehicle(driver), NOW.plus(Duration.ofDays(3)),
                NOW.plus(Duration.ofDays(3)).plusSeconds(3600));
        second.markPaid(true);
        second.checkIn(NOW.plus(Duration.ofDays(3)).plusSeconds(10));
        em.flush();
        UUID otherTicket = ticketFor(second);

        assertThatThrownBy(() -> commands.chargePenalty(support.getId(), otherTicket, id, "Wrong ticket"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> commands.close(support.getId(), ticketId, "Done"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("PENALTY_PENDING"));

        commands.waiveAssessedPenalty(support.getId(), ticketId, id, "Goodwill");
        assertThat(commands.close(support.getId(), ticketId, "Done").status().name()).isEqualTo("CLOSED");
    }

    @Test
    void everyPenaltyActionIsAuditedWithItsReason() {
        UUID id = assess(2500, "op-1");
        commands.chargePenalty(support.getId(), ticketId, id, "Collect");
        UUID second = assess(1000, "op-2");
        commands.waiveAssessedPenalty(support.getId(), ticketId, second, "Goodwill");

        assertThat(tickets.auditTrail(support.getId(), ticketId, 0, 50).items()).extracting(AuditEventView::action)
                .containsSubsequence("SUPPORT_ASSESS_PENALTY", "SUPPORT_CHARGE_PENALTY_ASSESSED", "SUPPORT_ASSESS_PENALTY",
                        "SUPPORT_WAIVE_PENALTY_ASSESSED");
        assertThat(tickets.auditTrail(support.getId(), ticketId, 0, 50).items())
                .filteredOn(e -> e.action().equals("SUPPORT_WAIVE_PENALTY_ASSESSED"))
                .singleElement().satisfies(e -> assertThat(e.reason()).isEqualTo("Goodwill"));
    }

    @Test
    void supportSeesPenaltiesAndEvacuationTotalsInTheReservationContext() {
        UUID id = assess(2500, "op-1");
        commands.chargePenalty(support.getId(), ticketId, id, "Collect");
        em.clear();

        SupportReservationView context = tickets.reservationOf(support.getId(), ticketId);

        assertThat(context.penalties()).singleElement().satisfies(p -> assertThat(p.state()).isEqualTo(PenaltyState.CHARGED));
        assertThat(context.penaltyTotal()).isEqualByComparingTo("25.00");
        assertThat(context.evacuationFees()).isEmpty();
        assertThat(context.evacuationFeeTotal()).isEqualByComparingTo("0.00");
        // the parking price is not touched by either
        assertThat(context.lifecycle().spotCost()).isEqualByComparingTo(reservation.getSpotCost().toDecimal());
    }

    // ---- PARI-88

    @Test
    void severalEvacuationFeesAreKeptAndTheTotalIsTheirSum() {
        startEvacuation();

        commands.addEvacuationFee(support.getId(), ticketId, 15000, "Tow", "fee-1");
        em.clear();
        TicketView view = commands.addEvacuationFee(support.getId(), ticketId, 4000, "Second trip", "fee-2");
        em.clear();
        view = tickets.get(support.getId(), ticketId);

        assertThat(view.evacuationFees()).hasSize(2).extracting(SupportCharges.FeeItem::kind)
                .containsOnly(EvacuationFee.Kind.FEE);
        assertThat(view.evacuationFeeTotal()).isEqualByComparingTo("190.00");
        assertThat(view.penaltyTotal()).isEqualByComparingTo("0.00");
        assertThat(paymentsOfType(PaymentType.EVAC_FEE)).isEqualTo(2);
        assertThat(paymentsOfType(PaymentType.PENALTY)).isZero();
    }

    @Test
    void theFeePlannedForTheEvacuationIsChargedOnDoneAsItsOwnItemAndNeverTwice() {
        startEvacuation();
        commands.adjustEvacuationFee(support.getId(), ticketId, 12000, "Agreed price");
        commands.advanceEvacuation(support.getId(), ticketId, EvacuationStatus.IN_PROGRESS, "On the way");

        TicketView done = commands.advanceEvacuation(support.getId(), ticketId, EvacuationStatus.DONE, "Car moved");
        em.clear();

        assertThat(done.evacuationFees()).singleElement().satisfies(f -> assertThat(f.amount()).isEqualByComparingTo("120.00"));
        assertThat(paymentsOfType(PaymentType.EVAC_FEE)).isEqualTo(1);
        assertThatThrownBy(() -> commands.advanceEvacuation(support.getId(), ticketId, EvacuationStatus.DONE, "Again"))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThat(paymentsOfType(PaymentType.EVAC_FEE)).isEqualTo(1);
    }

    @Test
    void theEvacuationFeeCannotBeOverwrittenAfterItWasCharged() {
        startEvacuation();
        commands.adjustEvacuationFee(support.getId(), ticketId, 12000, "Agreed price");
        commands.advanceEvacuation(support.getId(), ticketId, EvacuationStatus.IN_PROGRESS, "On the way");
        commands.advanceEvacuation(support.getId(), ticketId, EvacuationStatus.DONE, "Car moved");

        assertThatThrownBy(() -> commands.adjustEvacuationFee(support.getId(), ticketId, 100, "Silent change"))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void retryingAFeeOperationCreatesNeitherASecondItemNorASecondPayment() {
        startEvacuation();
        commands.addEvacuationFee(support.getId(), ticketId, 15000, "Tow", "fee-1");
        em.clear();

        TicketView again = commands.addEvacuationFee(support.getId(), ticketId, 15000, "Tow", "fee-1");

        assertThat(again.evacuationFees()).hasSize(1);
        assertThat(count("evacuation_fees")).isEqualTo(1);
        assertThat(paymentsOfType(PaymentType.EVAC_FEE)).isEqualTo(1);
        assertThat(commandAudits("SUPPORT_EVACUATION_FEE_ADDED")).isEqualTo(1);
    }

    @Test
    void aRefundIsAnExplicitItemThatKeepsTheOriginalAndLowersTheTotalOnce() {
        startEvacuation();
        TicketView added = commands.addEvacuationFee(support.getId(), ticketId, 15000, "Tow", "fee-1");
        em.clear();
        UUID feeId = tickets.get(support.getId(), ticketId).evacuationFees().get(0).id();

        commands.refundEvacuationFee(support.getId(), ticketId, feeId, "Charged by mistake");
        em.clear();
        TicketView view = tickets.get(support.getId(), ticketId);

        assertThat(view.evacuationFees()).hasSize(2);
        assertThat(view.evacuationFees()).extracting(SupportCharges.FeeItem::kind)
                .containsExactly(EvacuationFee.Kind.FEE, EvacuationFee.Kind.REFUND);
        assertThat(view.evacuationFees().get(1).refundsFeeId()).isEqualTo(feeId);
        assertThat(view.evacuationFees().get(0).amount()).isEqualByComparingTo("150.00");
        assertThat(view.evacuationFeeTotal()).isEqualByComparingTo("0.00");
        assertThat(paymentsOfType(PaymentType.REFUND)).isEqualTo(1);

        assertThatThrownBy(() -> commands.refundEvacuationFee(support.getId(), ticketId, feeId, "Again"))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("EVACUATION_FEE_ALREADY_REFUNDED"));
        assertThat(paymentsOfType(PaymentType.REFUND)).isEqualTo(1);
        assertThat(added.evacuationFees()).hasSize(1);
    }

    @Test
    void aCorrectionIsARefundPlusANewFeeAndEveryStepIsAudited() {
        startEvacuation();
        commands.addEvacuationFee(support.getId(), ticketId, 15000, "Tow", "fee-1");
        em.clear();
        UUID feeId = tickets.get(support.getId(), ticketId).evacuationFees().get(0).id();
        commands.refundEvacuationFee(support.getId(), ticketId, feeId, "Wrong amount");
        commands.addEvacuationFee(support.getId(), ticketId, 9000, "Right amount", "fee-2");
        em.clear();

        TicketView view = tickets.get(support.getId(), ticketId);

        assertThat(view.evacuationFees()).hasSize(3);
        assertThat(view.evacuationFeeTotal()).isEqualByComparingTo("90.00");
        assertThat(tickets.auditTrail(support.getId(), ticketId, 0, 50).items()).extracting(AuditEventView::action)
                .containsSubsequence("SUPPORT_EVACUATION_FEE_ADDED", "SUPPORT_EVACUATION_FEE_REFUNDED", "SUPPORT_EVACUATION_FEE_ADDED");
    }

    @Test
    void aDeclinedFeeIsRecordedButNeverCountsAndCannotBeRefunded() {
        jdbc.update("update payment_methods set provider_ref = ? where user_id = ?", MockPaymentGateway.DECLINED_TOKEN, driver.getId());
        em.clear();
        startEvacuation();

        commands.addEvacuationFee(support.getId(), ticketId, 15000, "Tow", "fee-1");
        em.clear();
        TicketView view = tickets.get(support.getId(), ticketId);

        assertThat(view.evacuationFees()).singleElement().satisfies(f -> assertThat(f.status()).isEqualTo(EvacuationFee.Status.FAILED));
        assertThat(view.evacuationFeeTotal()).isEqualByComparingTo("0.00");
        UUID feeId = view.evacuationFees().get(0).id();
        assertThatThrownBy(() -> commands.refundEvacuationFee(support.getId(), ticketId, feeId, "Nothing to refund"))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void feesNeedAnEvacuationASupportEmployeeAndAnOperationId() {
        assertThatThrownBy(() -> commands.addEvacuationFee(support.getId(), ticketId, 15000, "Tow", "fee-1"))
                .isInstanceOf(IllegalStateTransitionException.class);
        startEvacuation();
        assertThatThrownBy(() -> commands.addEvacuationFee(owner.getId(), ticketId, 15000, "Tow", "fee-1"))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> commands.addEvacuationFee(support.getId(), ticketId, 15000, "Tow", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> commands.addEvacuationFee(support.getId(), ticketId, 0, "Tow", "fee-0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> commands.refundEvacuationFee(owner.getId(), ticketId, UUID.randomUUID(), "x"))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> commands.refundEvacuationFee(support.getId(), ticketId, UUID.randomUUID(), "x"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void evacuationFeesAndPenaltiesStayApartFromTheParkingPrice() {
        startEvacuation();
        commands.addEvacuationFee(support.getId(), ticketId, 15000, "Tow", "fee-1");
        UUID id = assess(2500, "op-1");
        commands.chargePenalty(support.getId(), ticketId, id, "Collect");
        em.clear();

        SupportReservationView context = tickets.reservationOf(support.getId(), ticketId);

        assertThat(context.evacuationFeeTotal()).isEqualByComparingTo("150.00");
        assertThat(context.penaltyTotal()).isEqualByComparingTo("25.00");
        assertThat(context.lifecycle().spotCost()).isEqualByComparingTo(reservation.getSpotCost().toDecimal());
        assertThat(context.lifecycle().total()).isEqualByComparingTo(reservation.getTotal().toDecimal());
        BigDecimal paid = context.payments().stream().filter(p -> p.type() == PaymentType.EVAC_FEE)
                .map(SupportReservationView.PaymentLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(paid).isEqualByComparingTo("150.00");
    }

    // ---- helpers

    private UUID assess(long amountMinor, String operationId) {
        TicketView view = commands.assessPenalty(support.getId(), ticketId, amountMinor, null, "Reason", operationId);
        em.flush();
        em.clear();
        return tickets.get(support.getId(), ticketId).penalties().stream()
                .filter(p -> p.state() == PenaltyState.PENDING && p.amount().compareTo(BigDecimal.valueOf(amountMinor, 2)) == 0)
                .reduce((a, b) -> b).orElseThrow(() -> new AssertionError("no penalty in " + view)).id();
    }

    private void startEvacuation() {
        commands.requestEvacuation(support.getId(), ticketId, "Public car park", "Driver unreachable");
        em.flush();
        em.clear();
    }

    private UUID ticketFor(Reservation r) {
        UUID id = tickets.create(owner.getId(), r.getId(), TicketType.OTHER, "Report").id();
        em.flush();
        return id;
    }

    private long paymentsOfType(PaymentType type) {
        return jdbc.queryForObject("select count(*) from payments where type = ? and reservation_id = ?", Long.class,
                type.name(), reservation.getId());
    }

    private java.util.List<PaymentStatus> paymentStatuses(PaymentType type) {
        return jdbc.queryForList("select status from payments where type = ? and reservation_id = ?", String.class, type.name(),
                reservation.getId()).stream().map(PaymentStatus::valueOf).toList();
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private long commandAudits(String action) {
        return jdbc.queryForObject("select count(*) from audit_log where action = ?", Long.class, action);
    }
}
