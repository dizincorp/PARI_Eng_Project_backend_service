package pl.pollub.pari.support.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.ForbiddenException;
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

/** PARI-100: support refunds an automatic overstay penalty that was charged. Real schema, real services, mock gateway. */
@Import({SupportTicketService.class, SupportCommandService.class, ModerationService.class})
class OverstayPenaltyRefundTest extends ServiceTestBase {

    private static final long PENALTY = 1500;

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
    PaymentMethod card;
    Reservation reservation;
    UUID ticketId;

    @BeforeEach
    void setUp() {
        owner = persistUser(Role.OWNER);
        driver = persistUser(Role.DRIVER);
        support = persistUser(Role.SUPPORT);
        spot = persistSpot(owner, 51.2465, 22.5684, 1000);
        card = new PaymentMethod(driver, "pm_card_visa", "visa", "4242", 12, 2030);
        card.markDefault(true);
        em.persist(card);
        reservation = runningReservation();
    }

    // ---- success

    @Test
    void aChargedOverstayPenaltyIsRefundedInFullAndTheBilledAmountsStay() {
        chargeOverstay();
        long finalBefore = column("final_cost_minor");
        long penaltyBefore = column("overstay_penalty_minor");
        long payoutsBefore = count("payouts");

        TicketView view = commands.refundOverstayPenalty(support.getId(), ticketId, "The gate was blocked", "op-1");
        em.flush();
        em.clear();

        assertThat(view.overstayPenalty().state()).isEqualTo(OverstayPenaltyView.State.REFUNDED);
        assertThat(view.overstayPenalty().amount()).isEqualByComparingTo("15.00");
        assertThat(view.overstayPenalty().refundedAmount()).isEqualByComparingTo("15.00");
        assertThat(view.overstayPenalty().netAmount()).isEqualByComparingTo("0.00");
        assertThat(view.overstayPenalty().refundedAt()).isEqualTo(NOW);
        assertThat(view.overstayPenalty().currency()).isEqualTo("PLN");

        assertThat(paymentsOfType(PaymentType.REFUND)).containsExactly(PaymentStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("select amount_minor from payments where type = 'REFUND' and reservation_id = ?", Long.class,
                reservation.getId())).isEqualTo(PENALTY);
        assertThat(paymentsOfType(PaymentType.PENALTY)).containsExactly(PaymentStatus.SUCCEEDED);
        // the totals say what was billed; the owner's side is not touched
        assertThat(column("overstay_penalty_minor")).isEqualTo(penaltyBefore).isEqualTo(PENALTY);
        assertThat(column("final_cost_minor")).isEqualTo(finalBefore);
        assertThat(count("payouts")).isEqualTo(payoutsBefore);
    }

    @Test
    void theReservationContextShowsTheSameStateAsTheTicket() {
        chargeOverstay();
        assertThat(tickets.reservationOf(support.getId(), ticketId).overstayPenalty().state()).isEqualTo(OverstayPenaltyView.State.CHARGED);
        assertThat(tickets.reservationOf(support.getId(), ticketId).overstayPenalty().netAmount()).isEqualByComparingTo("15.00");

        commands.refundOverstayPenalty(support.getId(), ticketId, "Mistake", "op-1");
        em.flush();
        em.clear();

        SupportReservationView context = tickets.reservationOf(support.getId(), ticketId);
        assertThat(context.overstayPenalty().state()).isEqualTo(OverstayPenaltyView.State.REFUNDED);
        assertThat(context.overstayPenalty().refundedAmount()).isEqualByComparingTo("15.00");
        assertThat(context.refundState()).isEqualTo("REFUNDED");
        assertThat(context.lifecycle().overstayPenalty()).isEqualByComparingTo("15.00");
        assertThat(tickets.get(support.getId(), ticketId).overstayPenalty().state()).isEqualTo(OverstayPenaltyView.State.REFUNDED);
    }

    @Test
    void theAuditEntryHasTheActorTheReasonTheAmountTheOperationAndTheOutcome() {
        chargeOverstay();

        commands.refundOverstayPenalty(support.getId(), ticketId, "The gate was blocked", "op-77");
        em.flush();
        em.clear();

        assertThat(tickets.auditTrail(support.getId(), ticketId, 0, 50).items())
                .filteredOn(e -> e.action().equals("SUPPORT_REFUND_OVERSTAY_PENALTY"))
                .singleElement().satisfies(e -> {
                    assertThat(e.actorId()).isEqualTo(support.getId());
                    assertThat(e.reason()).isEqualTo("The gate was blocked");
                    assertThat(e.outcome()).isEqualTo("SUCCEEDED");
                    assertThat(e.metadata()).containsEntry("operationId", "op-77");
                    assertThat(((Number) e.metadata().get("amountMinor")).longValue()).isEqualTo(PENALTY);
                    assertThat(e.metadata().get("targetUserId")).isEqualTo(driver.getId().toString());
                });
    }

    @Test
    void theDriverIsToldAboutTheRefundAndNobodyElse() {
        chargeOverstay();

        commands.refundOverstayPenalty(support.getId(), ticketId, "Mistake", "op-1");
        em.flush();

        assertThat(notificationsOf(driver)).singleElement().satisfies(body -> assertThat(body).contains("15.00").contains(reservation.getCode()));
        assertThat(notificationsOf(owner)).isEmpty();
        assertThat(notificationsOf(support)).isEmpty();
    }

    // ---- idempotency and duplicates

    @Test
    void sendingTheSameOperationAgainRefundsOnceAndChangesNothing() {
        chargeOverstay();
        commands.refundOverstayPenalty(support.getId(), ticketId, "Mistake", "op-1");
        em.flush();
        em.clear();

        TicketView again = commands.refundOverstayPenalty(support.getId(), ticketId, "Mistake", "op-1");
        em.flush();

        assertThat(again.overstayPenalty().state()).isEqualTo(OverstayPenaltyView.State.REFUNDED);
        assertThat(paymentsOfType(PaymentType.REFUND)).hasSize(1);
        assertThat(audits("SUPPORT_REFUND_OVERSTAY_PENALTY")).isEqualTo(1);
        assertThat(notificationsOf(driver)).hasSize(1);
    }

    @Test
    void anotherOperationAfterTheRefundIsRejectedAndRefundsNothing() {
        chargeOverstay();
        commands.refundOverstayPenalty(support.getId(), ticketId, "Mistake", "op-1");
        em.flush();
        em.clear();

        assertThatThrownBy(() -> commands.refundOverstayPenalty(support.getId(), ticketId, "Again", "op-2"))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("OVERSTAY_PENALTY_ALREADY_REFUNDED"));
        assertThat(paymentsOfType(PaymentType.REFUND)).hasSize(1);
        assertThat(audits("SUPPORT_REFUND_OVERSTAY_PENALTY")).isEqualTo(1);
    }

    // ---- states that cannot be refunded

    @Test
    void aReservationWithoutAnOverstayPenaltyCannotBeRefunded() {
        assertRejected("NONE");
    }

    @Test
    void aHeldPenaltyMustBeSettledWithSettlePenaltyNotRefunded() {
        reservation.applyOverstayPenalty(Money.pln(PENALTY));
        reservation.checkOut(NOW);
        reservation.holdPenalty();
        em.flush();
        em.clear();

        assertRejected("PENDING");
    }

    @Test
    void aWaivedPenaltyCannotBeRefunded() {
        reservation.applyOverstayPenalty(Money.pln(PENALTY));
        reservation.checkOut(NOW);
        reservation.holdPenalty();
        reservation.settlePenalty(true);
        em.flush();
        em.clear();

        assertRejected("WAIVED");
    }

    @Test
    void aPenaltyTheProviderRefusedCannotBeRefunded() {
        jdbc.update("update payment_methods set provider_ref = ? where user_id = ?", MockPaymentGateway.DECLINED_TOKEN, driver.getId());
        em.clear();
        reservation = em.find(Reservation.class, reservation.getId());
        chargeOverstay(MockPaymentGateway.DECLINED_TOKEN);
        assertThat(paymentsOfType(PaymentType.PENALTY)).containsExactly(PaymentStatus.FAILED);

        assertRejected("FAILED");
    }

    @Test
    void aPenaltyAssessedBySupportIsNotTheOverstayPenaltyAndStaysUntouched() {
        commands.assessPenalty(support.getId(), ticketId, 2500, null, "Blocked the gate", "p-1");
        em.flush();
        em.clear();
        UUID assessed = tickets.get(support.getId(), ticketId).penalties().get(0).id();
        commands.chargePenalty(support.getId(), ticketId, assessed, "Collect");
        em.flush();
        em.clear();

        assertRejected("NONE");

        TicketView view = tickets.get(support.getId(), ticketId);
        assertThat(view.penalties()).singleElement().satisfies(p -> assertThat(p.state()).isEqualTo(PenaltyState.CHARGED));
        assertThat(view.penaltyTotal()).isEqualByComparingTo("25.00");
    }

    @Test
    void whenBothExistOnlyTheOverstayPenaltyIsRefundedAndTheAssessedOneStaysCharged() {
        commands.assessPenalty(support.getId(), ticketId, 2500, null, "Blocked the gate", "p-1");
        em.flush();
        em.clear();
        UUID assessed = tickets.get(support.getId(), ticketId).penalties().get(0).id();
        commands.chargePenalty(support.getId(), ticketId, assessed, "Collect");
        em.flush();
        em.clear();
        reservation = em.find(Reservation.class, reservation.getId());
        chargeOverstay();

        TicketView view = commands.refundOverstayPenalty(support.getId(), ticketId, "Overstay was not the driver's fault", "op-1");
        em.flush();
        em.clear();

        assertThat(jdbc.queryForObject("select amount_minor from payments where type = 'REFUND' and reservation_id = ?", Long.class,
                reservation.getId())).isEqualTo(PENALTY);
        assertThat(view.overstayPenalty().state()).isEqualTo(OverstayPenaltyView.State.REFUNDED);
        TicketView after = tickets.get(support.getId(), ticketId);
        assertThat(after.penalties()).singleElement().satisfies(p -> assertThat(p.state()).isEqualTo(PenaltyState.CHARGED));
        assertThat(after.penaltyTotal()).isEqualByComparingTo("25.00");
        assertThat(paymentsOfType(PaymentType.PENALTY)).containsExactly(PaymentStatus.SUCCEEDED, PaymentStatus.SUCCEEDED);
    }

    // ---- who and how

    @Test
    void onlySupportOrAdminAndOnlyWithAReasonAnOperationIdAndAnOpenTicket() {
        chargeOverstay();

        assertThatThrownBy(() -> commands.refundOverstayPenalty(owner.getId(), ticketId, "r", "op")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> commands.refundOverstayPenalty(driver.getId(), ticketId, "r", "op")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> commands.refundOverstayPenalty(support.getId(), ticketId, " ", "op")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> commands.refundOverstayPenalty(support.getId(), ticketId, "r", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> commands.refundOverstayPenalty(support.getId(), ticketId, "r", null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(paymentsOfType(PaymentType.REFUND)).isEmpty();

        User admin = persistUser(Role.ADMIN);
        em.flush();
        assertThat(commands.refundOverstayPenalty(admin.getId(), ticketId, "Admin decision", "op-admin").overstayPenalty().state())
                .isEqualTo(OverstayPenaltyView.State.REFUNDED);
    }

    @Test
    void aClosedTicketCannotRefund() {
        chargeOverstay();
        commands.close(support.getId(), ticketId, "Resolved");
        em.flush();
        em.clear();

        assertThatThrownBy(() -> commands.refundOverstayPenalty(support.getId(), ticketId, "r", "op"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("TICKET_CLOSED"));
        assertThat(paymentsOfType(PaymentType.REFUND)).isEmpty();
    }

    @Test
    void aRefusedRefundIsAPaymentFailureAndLeavesNoRefundOrMarker() {
        chargeOverstay();
        jdbc.update("update payments set provider_ref = 'unknown-ref' where type = 'PENALTY' and reservation_id = ?", reservation.getId());
        em.clear();

        assertThatThrownBy(() -> commands.refundOverstayPenalty(support.getId(), ticketId, "r", "op-1"))
                .isInstanceOf(PaymentFailedException.class);
        em.clear();

        assertThat(paymentsOfType(PaymentType.REFUND)).isEmpty();
        assertThat(jdbc.queryForObject("select overstay_refund_payment_id from reservations where id = ?", UUID.class, reservation.getId())).isNull();
        assertThat(audits("SUPPORT_REFUND_OVERSTAY_PENALTY")).isZero();
        assertThat(notificationsOf(driver)).isEmpty();
        assertThat(tickets.get(support.getId(), ticketId).overstayPenalty().state()).isEqualTo(OverstayPenaltyView.State.CHARGED);
    }

    @Test
    void aReservationWithNoPenaltyShowsStateNoneInBothViews() {
        assertThat(tickets.get(support.getId(), ticketId).overstayPenalty().state()).isEqualTo(OverstayPenaltyView.State.NONE);
        assertThat(tickets.get(support.getId(), ticketId).overstayPenalty().amount()).isEqualByComparingTo("0.00");
        assertThat(tickets.reservationOf(support.getId(), ticketId).overstayPenalty().refundedAmount()).isNull();
    }

    // ---- helpers

    private Reservation runningReservation() {
        Vehicle vehicle = persistVehicle(driver);
        Reservation r = persistReservation(spot, driver, vehicle, NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(20)));
        r.markPaid(true);
        r.checkIn(NOW.minus(Duration.ofHours(3)).plusSeconds(60));
        em.flush();
        ticketId = tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there").id();
        em.flush();
        return r;
    }

    /** The driver stayed over: the penalty is billed and charged to the saved card (the result depends on the card). */
    private void chargeOverstay() {
        chargeOverstay("pm_card_visa");
    }

    private void chargeOverstay(String token) {
        reservation.applyOverstayPenalty(Money.pln(PENALTY));
        reservation.checkOut(NOW);
        paymentService.chargePenalty(reservation, Money.pln(PENALTY), token);
        em.flush();
        em.clear();
    }

    private void assertRejected(String state) {
        assertThatThrownBy(() -> commands.refundOverstayPenalty(support.getId(), ticketId, "Try", "op-x"))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("OVERSTAY_PENALTY_NOT_CHARGED");
                    assertThat(e.getDetails()).containsEntry("state", state);
                });
        em.clear();
        assertThat(paymentsOfType(PaymentType.REFUND)).isEmpty();
        assertThat(audits("SUPPORT_REFUND_OVERSTAY_PENALTY")).isZero();
        assertThat(notificationsOf(driver)).isEmpty();
    }

    private List<PaymentStatus> paymentsOfType(PaymentType type) {
        em.flush();
        return jdbc.queryForList("select status from payments where type = ? and reservation_id = ? order by created_at, id", String.class,
                type.name(), reservation.getId()).stream().map(PaymentStatus::valueOf).toList();
    }

    private long column(String name) {
        em.flush();
        return jdbc.queryForObject("select " + name + " from reservations where id = ?", Long.class, reservation.getId());
    }

    private long count(String table) {
        em.flush();
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private long audits(String action) {
        em.flush();
        return jdbc.queryForObject("select count(*) from audit_log where action = ?", Long.class, action);
    }

    private List<String> notificationsOf(User user) {
        em.flush();
        return jdbc.queryForList("select body from notifications where user_id = ? and type = 'OVERSTAY_PENALTY_REFUNDED'", String.class,
                user.getId());
    }

}
