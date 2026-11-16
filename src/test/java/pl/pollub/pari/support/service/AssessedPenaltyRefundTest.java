package pl.pollub.pari.support.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.ForbiddenException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.payment.domain.PaymentStatus;
import pl.pollub.pari.payment.domain.PaymentType;
import pl.pollub.pari.payment.gateway.MockPaymentGateway;
import pl.pollub.pari.payment.service.PaymentFailedException;
import pl.pollub.pari.payment.service.PaymentService;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.support.ServiceTestBase;
import pl.pollub.pari.support.domain.PenaltyState;
import pl.pollub.pari.support.domain.TicketType;
import pl.pollub.pari.user.domain.PaymentMethod;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PARI-101: support refunds a manual (assessed) penalty that was charged. Real schema, real services, mock gateway. */
@Import({SupportTicketService.class, SupportCommandService.class, ModerationService.class})
class AssessedPenaltyRefundTest extends ServiceTestBase {

    @Autowired
    SupportTicketService tickets;
    @Autowired
    SupportCommandService commands;
    @Autowired
    PaymentService paymentService;

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

    // ---- success

    @Test
    void aChargedPenaltyIsRefundedInFullAndKeepsItsAmountAndChargeHistory() {
        UUID id = chargedPenalty(2500, "p-1");

        TicketView view = commands.refundAssessedPenalty(support.getId(), ticketId, id, "Charged by mistake", "op-1");
        em.flush();
        em.clear();

        assertThat(view.penalties()).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo(id);
            assertThat(p.state()).isEqualTo(PenaltyState.REFUNDED);
            assertThat(p.amount()).isEqualByComparingTo("25.00");
            assertThat(p.currency()).isEqualTo("PLN");
            assertThat(p.targetUserId()).isEqualTo(driver.getId());
            assertThat(p.refundedAmount()).isEqualByComparingTo("25.00");
            assertThat(p.refundedAt()).isEqualTo(NOW);
            assertThat(p.refundReason()).isEqualTo("Charged by mistake");
            // the charge decision is still on record
            assertThat(p.decisionReason()).isEqualTo("Collect");
            assertThat(p.decidedAt()).isNotNull();
        });
        assertThat(view.penaltyTotal()).isEqualByComparingTo("0.00");
        assertThat(payments(PaymentType.REFUND)).containsExactly(PaymentStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("select amount_minor from payments where type = 'REFUND' and reservation_id = ?", Long.class,
                reservation.getId())).isEqualTo(2500);
        assertThat(payments(PaymentType.PENALTY)).containsExactly(PaymentStatus.SUCCEEDED);

        SupportReservationView context = tickets.reservationOf(support.getId(), ticketId);
        assertThat(context.penalties()).singleElement().satisfies(p -> assertThat(p.state()).isEqualTo(PenaltyState.REFUNDED));
        assertThat(context.penaltyTotal()).isEqualByComparingTo("0.00");
        assertThat(context.refundState()).isEqualTo("REFUNDED");
        assertThat(context.lifecycle().spotCost()).isEqualByComparingTo(reservation.getSpotCost().toDecimal());
    }

    @Test
    void theAuditEntryHasTheActorTheReasonTheAmountTheOperationAndTheOutcome() {
        UUID id = chargedPenalty(2500, "p-1");

        commands.refundAssessedPenalty(support.getId(), ticketId, id, "Charged by mistake", "op-77");
        em.flush();
        em.clear();

        assertThat(tickets.auditTrail(support.getId(), ticketId, 0, 50).items())
                .filteredOn(e -> e.action().equals("SUPPORT_REFUND_PENALTY_ASSESSED"))
                .singleElement().satisfies(e -> {
                    assertThat(e.actorId()).isEqualTo(support.getId());
                    assertThat(e.reason()).isEqualTo("Charged by mistake");
                    assertThat(e.outcome()).isEqualTo("SUCCEEDED");
                    assertThat(e.createdAt()).isNotNull();
                    assertThat(e.metadata()).containsEntry("operationId", "op-77").containsEntry("penaltyId", id.toString())
                            .containsEntry("penaltyState", "REFUNDED");
                    assertThat(((Number) e.metadata().get("amountMinor")).longValue()).isEqualTo(2500);
                });
    }

    @Test
    void theDriverIsToldAboutTheRefundAndNobodyElse() {
        UUID id = chargedPenalty(2500, "p-1");

        commands.refundAssessedPenalty(support.getId(), ticketId, id, "Charged by mistake", "op-1");

        assertThat(notificationsOf(driver)).singleElement().satisfies(body -> assertThat(body).contains("25.00").contains(reservation.getCode()));
        assertThat(notificationsOf(owner)).isEmpty();
        assertThat(notificationsOf(support)).isEmpty();
    }

    // ---- idempotency

    @Test
    void sendingTheSameOperationAgainRefundsOnceAndChangesNothing() {
        UUID id = chargedPenalty(2500, "p-1");
        commands.refundAssessedPenalty(support.getId(), ticketId, id, "Mistake", "op-1");
        em.flush();
        em.clear();

        TicketView again = commands.refundAssessedPenalty(support.getId(), ticketId, id, "Mistake", "op-1");

        assertThat(again.penalties()).singleElement().satisfies(p -> assertThat(p.state()).isEqualTo(PenaltyState.REFUNDED));
        assertThat(payments(PaymentType.REFUND)).hasSize(1);
        assertThat(audits("SUPPORT_REFUND_PENALTY_ASSESSED")).isEqualTo(1);
        assertThat(notificationsOf(driver)).hasSize(1);
    }

    @Test
    void anotherOperationAfterTheRefundIsRejectedAndRefundsNothing() {
        UUID id = chargedPenalty(2500, "p-1");
        commands.refundAssessedPenalty(support.getId(), ticketId, id, "Mistake", "op-1");
        em.flush();
        em.clear();

        assertThatThrownBy(() -> commands.refundAssessedPenalty(support.getId(), ticketId, id, "Again", "op-2"))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("PENALTY_ALREADY_REFUNDED"));
        assertThat(payments(PaymentType.REFUND)).hasSize(1);
        assertThat(audits("SUPPORT_REFUND_PENALTY_ASSESSED")).isEqualTo(1);
    }

    // ---- states that cannot be refunded

    @Test
    void aPendingPenaltyCannotBeRefunded() {
        UUID id = assess(2500, "p-1");

        assertRejected(id, "PENDING");
    }

    @Test
    void aWaivedPenaltyCannotBeRefunded() {
        UUID id = assess(2500, "p-1");
        commands.waiveAssessedPenalty(support.getId(), ticketId, id, "Goodwill");
        em.flush();
        em.clear();

        assertRejected(id, "WAIVED");
    }

    @Test
    void aPenaltyTheProviderRefusedCannotBeRefunded() {
        jdbc.update("update payment_methods set provider_ref = ? where user_id = ?", MockPaymentGateway.DECLINED_TOKEN, driver.getId());
        em.clear();
        UUID id = assess(2500, "p-1");
        commands.chargePenalty(support.getId(), ticketId, id, "Try to collect");
        em.flush();
        em.clear();

        assertRejected(id, "FAILED");
    }

    @Test
    void aPenaltyOfAnotherTicketOrAnUnknownOneIsNotFound() {
        UUID id = chargedPenalty(2500, "p-1");
        Reservation second = persistReservation(spot, driver, persistVehicle(driver), NOW.plus(Duration.ofDays(3)),
                NOW.plus(Duration.ofDays(3)).plusSeconds(3600));
        second.markPaid(true);
        em.flush();
        UUID otherTicket = tickets.create(owner.getId(), second.getId(), TicketType.OTHER, "Report").id();
        em.flush();

        assertThatThrownBy(() -> commands.refundAssessedPenalty(support.getId(), otherTicket, id, "Wrong ticket", "op-1"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> commands.refundAssessedPenalty(support.getId(), ticketId, UUID.randomUUID(), "Unknown", "op-1"))
                .isInstanceOf(NotFoundException.class);
        assertThat(payments(PaymentType.REFUND)).isEmpty();
    }

    // ---- independence from the other refund flows

    @Test
    void refundingTheAssessedPenaltyLeavesTheOverstayPenaltyAndEvacuationFeeAlone() {
        UUID id = chargedPenalty(2500, "p-1");
        commands.requestEvacuation(support.getId(), ticketId, "Public car park", "Driver unreachable");
        commands.addEvacuationFee(support.getId(), ticketId, 15000, "Tow", "fee-1");
        em.flush();
        em.clear();
        reservation = em.find(Reservation.class, reservation.getId());
        reservation.applyOverstayPenalty(Money.pln(1500));
        reservation.checkOut(NOW);
        paymentService.chargePenalty(reservation, Money.pln(1500), "pm_card_visa");
        em.flush();
        em.clear();

        commands.refundAssessedPenalty(support.getId(), ticketId, id, "Mistake", "op-1");
        em.flush();
        em.clear();

        TicketView view = tickets.get(support.getId(), ticketId);
        assertThat(view.overstayPenalty().state()).isEqualTo(OverstayPenaltyView.State.CHARGED);
        assertThat(view.evacuationFeeTotal()).isEqualByComparingTo("150.00");
        assertThat(payments(PaymentType.REFUND)).hasSize(1);
        assertThat(jdbc.queryForObject("select amount_minor from payments where type = 'REFUND' and reservation_id = ?", Long.class,
                reservation.getId())).isEqualTo(2500);

        // and the overstay refund still refunds the overstay penalty, not the manual one
        TicketView overstay = commands.refundOverstayPenalty(support.getId(), ticketId, "Also a mistake", "op-2");
        em.flush();
        em.clear();
        assertThat(overstay.overstayPenalty().state()).isEqualTo(OverstayPenaltyView.State.REFUNDED);
        assertThat(overstay.overstayPenalty().refundedAmount()).isEqualByComparingTo("15.00");
        assertThat(payments(PaymentType.REFUND)).hasSize(2);
        assertThat(tickets.get(support.getId(), ticketId).penalties()).singleElement()
                .satisfies(p -> assertThat(p.state()).isEqualTo(PenaltyState.REFUNDED));
    }

    @Test
    void refundingTheOverstayPenaltyDoesNotTouchTheChargedManualPenalty() {
        UUID id = chargedPenalty(2500, "p-1");
        em.clear();
        reservation = em.find(Reservation.class, reservation.getId());
        reservation.applyOverstayPenalty(Money.pln(1500));
        reservation.checkOut(NOW);
        paymentService.chargePenalty(reservation, Money.pln(1500), "pm_card_visa");
        em.flush();
        em.clear();

        commands.refundOverstayPenalty(support.getId(), ticketId, "Mistake", "op-1");
        em.flush();
        em.clear();

        TicketView view = tickets.get(support.getId(), ticketId);
        assertThat(view.penalties()).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo(id);
            assertThat(p.state()).isEqualTo(PenaltyState.CHARGED);
        });
        assertThat(view.penaltyTotal()).isEqualByComparingTo("25.00");
    }

    // ---- who and how

    @Test
    void onlySupportOrAdminAndOnlyWithAReasonAndAnOperationId() {
        UUID id = chargedPenalty(2500, "p-1");

        assertThatThrownBy(() -> commands.refundAssessedPenalty(owner.getId(), ticketId, id, "r", "op")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> commands.refundAssessedPenalty(driver.getId(), ticketId, id, "r", "op")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> commands.refundAssessedPenalty(support.getId(), ticketId, id, " ", "op")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> commands.refundAssessedPenalty(support.getId(), ticketId, id, "r", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> commands.refundAssessedPenalty(support.getId(), ticketId, id, "r", null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(payments(PaymentType.REFUND)).isEmpty();

        User admin = persistUser(Role.ADMIN);
        em.flush();
        assertThat(commands.refundAssessedPenalty(admin.getId(), ticketId, id, "Admin decision", "op-admin").penalties())
                .singleElement().satisfies(p -> assertThat(p.state()).isEqualTo(PenaltyState.REFUNDED));
    }

    @Test
    void aClosedTicketCannotRefund() {
        UUID id = chargedPenalty(2500, "p-1");
        commands.close(support.getId(), ticketId, "Resolved");
        em.flush();
        em.clear();

        assertThatThrownBy(() -> commands.refundAssessedPenalty(support.getId(), ticketId, id, "r", "op"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("TICKET_CLOSED"));
        assertThat(payments(PaymentType.REFUND)).isEmpty();
    }

    @Test
    void aRefusedRefundIsAPaymentFailureAndLeavesNoRefundOrMarker() {
        UUID id = chargedPenalty(2500, "p-1");
        jdbc.update("update payments set provider_ref = 'unknown-ref' where type = 'PENALTY' and reservation_id = ?", reservation.getId());
        em.clear();

        assertThatThrownBy(() -> commands.refundAssessedPenalty(support.getId(), ticketId, id, "r", "op-1"))
                .isInstanceOf(PaymentFailedException.class);
        em.clear();

        assertThat(payments(PaymentType.REFUND)).isEmpty();
        assertThat(jdbc.queryForObject("select state from support_penalties where id = ?", String.class, id)).isEqualTo("CHARGED");
        assertThat(jdbc.queryForObject("select refund_payment_id from support_penalties where id = ?", UUID.class, id)).isNull();
        assertThat(audits("SUPPORT_REFUND_PENALTY_ASSESSED")).isZero();
        assertThat(notificationsOf(driver)).isEmpty();
    }

    // ---- helpers

    private UUID assess(long amountMinor, String operationId) {
        commands.assessPenalty(support.getId(), ticketId, amountMinor, null, "Blocked the gate", operationId);
        em.flush();
        em.clear();
        return tickets.get(support.getId(), ticketId).penalties().stream()
                .filter(p -> p.state() == PenaltyState.PENDING).reduce((a, b) -> b).orElseThrow().id();
    }

    private UUID chargedPenalty(long amountMinor, String operationId) {
        UUID id = assess(amountMinor, operationId);
        commands.chargePenalty(support.getId(), ticketId, id, "Collect");
        em.flush();
        em.clear();
        return id;
    }

    private void assertRejected(UUID penaltyId, String state) {
        assertThatThrownBy(() -> commands.refundAssessedPenalty(support.getId(), ticketId, penaltyId, "Try", "op-x"))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("PENALTY_NOT_CHARGED");
                    assertThat(e.getDetails()).containsEntry("state", state);
                });
        em.clear();
        assertThat(payments(PaymentType.REFUND)).isEmpty();
        assertThat(audits("SUPPORT_REFUND_PENALTY_ASSESSED")).isZero();
        assertThat(notificationsOf(driver)).isEmpty();
    }

    private List<PaymentStatus> payments(PaymentType type) {
        em.flush();
        return jdbc.queryForList("select status from payments where type = ? and reservation_id = ? order by created_at, id", String.class,
                type.name(), reservation.getId()).stream().map(PaymentStatus::valueOf).toList();
    }

    private long audits(String action) {
        em.flush();
        return jdbc.queryForObject("select count(*) from audit_log where action = ?", Long.class, action);
    }

    private List<String> notificationsOf(User user) {
        em.flush();
        return jdbc.queryForList("select body from notifications where user_id = ? and type = 'PENALTY_REFUNDED'", String.class, user.getId());
    }
}
