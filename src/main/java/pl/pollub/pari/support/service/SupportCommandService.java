package pl.pollub.pari.support.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.hibernate.Hibernate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.common.service.AuditTrail;
import pl.pollub.pari.common.service.PlatformSettings;
import pl.pollub.pari.common.domain.IllegalStateTransitionException;
import pl.pollub.pari.common.exception.BadRequestException;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.payment.domain.Payment;
import pl.pollub.pari.payment.domain.PaymentType;
import pl.pollub.pari.payment.service.PaymentService;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.service.ReservationService;
import pl.pollub.pari.support.domain.EvacuationFee;
import pl.pollub.pari.support.domain.EvacuationStatus;
import pl.pollub.pari.support.domain.PenaltyAssessment;
import pl.pollub.pari.support.domain.PenaltyState;
import pl.pollub.pari.support.domain.Report;
import pl.pollub.pari.support.domain.Sanction;
import pl.pollub.pari.support.domain.SanctionType;
import pl.pollub.pari.support.event.TicketEvent;
import pl.pollub.pari.support.repository.EvacuationFeeRepository;
import pl.pollub.pari.support.repository.PenaltyAssessmentRepository;
import pl.pollub.pari.support.repository.SanctionRepository;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.repository.UserRepository;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * What support can do about a ticket (PARI-71, PARI-72). Every command needs the SUPPORT (or ADMIN) role and a reason,
 * and is written to the audit trail with that reason. There is deliberately no command that edits raw data.
 */
@Service
public class SupportCommandService {

    private final SupportTicketService ticketService;
    private final ReservationService reservationService;
    private final UserRepository users;
    private final ModerationService moderation;
    private final AuditTrail audit;
    private final PlatformSettings settings;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final PenaltyAssessmentRepository penalties;
    private final EvacuationFeeRepository evacuationFees;
    private final PaymentService paymentService;
    private final EntityManager entityManager;

    public SupportCommandService(SupportTicketService ticketService, ReservationService reservationService, UserRepository users,
                                 ModerationService moderation, AuditTrail audit, PlatformSettings settings, Clock clock,
                                 ApplicationEventPublisher events, PenaltyAssessmentRepository penalties,
                                 EvacuationFeeRepository evacuationFees, PaymentService paymentService,
                                 EntityManager entityManager) {
        this.entityManager = entityManager;
        this.penalties = penalties;
        this.evacuationFees = evacuationFees;
        this.paymentService = paymentService;
        this.ticketService = ticketService;
        this.reservationService = reservationService;
        this.users = users;
        this.moderation = moderation;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
        this.events = events;
    }

    /** Ends the running session of the ticket's reservation. The penalty rules of a normal check-out apply. */
    @Transactional
    public TicketView forceCheckOut(UUID supportId, UUID ticketId, String reason) {
        Report ticket = begin(supportId, ticketId, reason);
        reservationService.supportForceCheckOut(supportId, ticket.getReservation().getId(), reason);
        return done(supportId, ticket, "FORCE_CHECK_OUT", reason);
    }

    /** Decides about a penalty that was held because the driver said they could not leave: waive it or charge it. */
    @Transactional
    public TicketView settlePenalty(UUID supportId, UUID ticketId, boolean waive, String reason) {
        Report ticket = begin(supportId, ticketId, reason);
        reservationService.supportSettlePenalty(supportId, ticket.getReservation().getId(), waive);
        return done(supportId, ticket, waive ? "WAIVE_PENALTY" : "CHARGE_PENALTY", reason);
    }

    /** Cancels a reservation that has not started running and refunds the driver in full. */
    @Transactional
    public TicketView cancelWithRefund(UUID supportId, UUID ticketId, String reason) {
        Report ticket = begin(supportId, ticketId, reason);
        reservationService.supportCancelWithRefund(supportId, ticket.getReservation().getId(), reason);
        return done(supportId, ticket, "CANCEL_WITH_REFUND", reason);
    }

    /** A NO_SHOW that was wrong: the reservation is cancelled and the driver is refunded. */
    @Transactional
    public TicketView correctNoShow(UUID supportId, UUID ticketId, String reason) {
        Report ticket = begin(supportId, ticketId, reason);
        reservationService.supportCorrectNoShow(supportId, ticket.getReservation().getId(), reason);
        return done(supportId, ticket, "CORRECT_NO_SHOW", reason);
    }

    /**
     * Warns (WARNING), blocks (BLOCK) or unblocks (UNBLOCK) a participant of the ticket's reservation: its driver, its owner
     * or the reporter. Any other user id is rejected with 400 SANCTION_TARGET_NOT_PARTICIPANT. The escalation rule is
     * described on {@link ModerationService}.
     */
    @Transactional
    public TicketView sanction(UUID supportId, UUID ticketId, UUID userId, SanctionType type, String reason) {
        Report ticket = begin(supportId, ticketId, reason);
        User target = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        if (!ticket.involves(target.getId())) {
            throw new pl.pollub.pari.common.exception.BadRequestException("SANCTION_TARGET_NOT_PARTICIPANT",
                    "The user is not a participant of this ticket's reservation");
        }
        boolean autoBlocked = moderation.apply(supportId, target, ticket, type, reason);
        java.util.Map<String, Object> extra = new java.util.HashMap<>();
        extra.put("targetUserId", userId.toString());
        extra.put("sanctionType", type.name());
        extra.put("targetBlocked", target.isBlocked());
        extra.put("autoBlocked", autoBlocked);
        return done(supportId, ticket, "SANCTION_" + type.name(), reason, extra);
    }

    /** Hands the ticket to another support employee. */
    @Transactional
    public TicketView reassign(UUID supportId, UUID ticketId, UUID newAssigneeId, String reason) {
        Report ticket = begin(supportId, ticketId, reason);
        Map<String, Object> extra = previousAssignee(ticket);
        ticket.reassign(ticketService.requireSupport(newAssigneeId));
        return done(supportId, ticket, "REASSIGN", reason, extra);
    }

    /** Releases a claimed ticket to the unassigned queue without closing it or changing its resolution (PARI-83). */
    @Transactional
    public TicketView release(UUID supportId, UUID ticketId, String reason) {
        Report ticket = begin(supportId, ticketId, reason);
        Map<String, Object> extra = previousAssignee(ticket);
        ticket.unassign();
        return done(supportId, ticket, "RELEASE", reason, extra);
    }

    /** Resolves and closes the ticket. The reason becomes the resolution shown to the participants. */
    @Transactional
    public TicketView close(UUID supportId, UUID ticketId, String reason) {
        Report ticket = begin(supportId, ticketId, reason);
        if (ticket.getReservation().isPenaltyPending()) {
            throw new pl.pollub.pari.common.exception.ConflictException("PENALTY_PENDING",
                    "Decide about the held penalty (waive or charge it) before closing the ticket");
        }
        if (penalties.existsByTicketIdAndState(ticketId, PenaltyState.PENDING)) {
            throw new ConflictException("PENALTY_PENDING",
                    "Charge or waive the penalty assessed on this ticket before closing it");
        }
        ticket.resolve(reason);
        ticket.close(clock.instant());
        return done(supportId, ticket, "CLOSE", reason);
    }

    /** Starts an evacuation to a public paid car park or an impound lot; the fee is the {@code evacuation_fee} setting. */
    @Transactional
    public TicketView requestEvacuation(UUID supportId, UUID ticketId, String destination, String reason) {
        Report ticket = begin(supportId, ticketId, reason);
        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("A destination is required");
        }
        Money fee = Money.pln(settings.decimal("evacuation_fee").movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact());
        ticket.requestEvacuation(destination, fee);
        events.publishEvent(TicketEvent.of(TicketEvent.Type.EVACUATION_REQUESTED, ticket, destination));
        return done(supportId, ticket, "EVACUATION_REQUESTED", reason);
    }

    /** REQUESTED to IN_PROGRESS to DONE. When it is DONE the driver is charged the evacuation fee (a failed charge is recorded, not thrown). */
    @Transactional
    public TicketView advanceEvacuation(UUID supportId, UUID ticketId, EvacuationStatus next, String reason) {
        Report ticket = begin(supportId, ticketId, reason);
        ticket.advanceEvacuation(next);
        if (next == EvacuationStatus.DONE && ticket.getEvacuationFee() != null && !ticket.getEvacuationFee().isZero()) {
            String key = "evacuation-fee:" + ticketId + ":evacuation-done";
            if (evacuationFees.findByOperationKey(key).isEmpty()) {
                chargeFee(supportId, ticket, ticket.getEvacuationFee(), reason, key);
            }
        }
        events.publishEvent(TicketEvent.of(TicketEvent.Type.EVACUATION_UPDATED, ticket, next.name()));
        return done(supportId, ticket, "EVACUATION_" + next.name(), reason);
    }

    /** Changes the evacuation fee, for example to waive it. */
    @Transactional
    public TicketView adjustEvacuationFee(UUID supportId, UUID ticketId, long feeMinor, String reason) {
        Report ticket = begin(supportId, ticketId, reason);
        ticket.adjustEvacuationFee(Money.pln(feeMinor));
        return done(supportId, ticket, "EVACUATION_FEE_ADJUSTED", reason);
    }

    // ---- penalties assessed by support (PARI-87)

    /**
     * Assesses a penalty on the ticket's reservation. It records a PENDING amount and does not collect anything; support
     * then charges or waives it. Allowed while the reservation is IN_PROGRESS, COMPLETED or NO_SHOW. The penalty is charged
     * to the reservation's driver (the only participant with a payment method); any other target is rejected with 400
     * PENALTY_TARGET_NOT_PAYER. {@code targetUserId} may be left out. {@code operationId} identifies the request: sending the
     * same one again returns the ticket unchanged and creates no second record. Amounts are PLN, in minor units.
     */
    @Transactional
    public TicketView assessPenalty(UUID supportId, UUID ticketId, long amountMinor, UUID targetUserId, String reason,
                                    String operationId) {
        Report ticket = begin(supportId, ticketId, reason);
        String key = operationKey("penalty", ticketId, operationId);
        if (penalties.findByOperationKey(key).isPresent()) {
            return TicketView.of(ticket, true);
        }
        Reservation reservation = ticket.getReservation();
        ReservationStatus status = reservation.getStatus();
        if (status != ReservationStatus.IN_PROGRESS && status != ReservationStatus.COMPLETED && status != ReservationStatus.NO_SHOW) {
            throw new ConflictException("PENALTY_RESERVATION_NOT_ELIGIBLE",
                    "A penalty can only be assessed on a reservation that is running, completed or a no-show, not " + status);
        }
        User driver = reservation.getDriver();
        if (targetUserId != null && !targetUserId.equals(driver.getId())) {
            throw new BadRequestException("PENALTY_TARGET_NOT_PAYER", "A penalty can only be assessed on the driver of the reservation");
        }
        PenaltyAssessment penalty = new PenaltyAssessment(ticket, driver, Money.pln(amountMinor), reason, key,
                users.getReferenceById(supportId));
        penalties.saveAndFlush(penalty);
        reservation.registerSupportPenalty(penalty);
        return done(supportId, ticket, "ASSESS_PENALTY", reason, penaltyData(penalty));
    }

    /** Collects a PENDING penalty from the driver now. The result is CHARGED, or FAILED when the payment was refused. */
    @Transactional
    public TicketView chargePenalty(UUID supportId, UUID ticketId, UUID penaltyId, String reason) {
        Report ticket = begin(supportId, ticketId, reason);
        PenaltyAssessment penalty = loadPenalty(ticket, penaltyId);
        requirePending(penalty, "be charged");
        Payment payment = reservationService.chargeDriver(ticket.getReservation(), PaymentType.PENALTY, penalty.getAmount(),
                "support-penalty:" + penalty.getId());
        penalty.settleWith(payment, users.getReferenceById(supportId), reason, clock.instant());
        penalties.saveAndFlush(penalty);
        Map<String, Object> extra = penaltyData(penalty);
        extra.put("paymentStatus", payment.getStatus().name());
        return done(supportId, ticket, "CHARGE_PENALTY_ASSESSED", reason, extra);
    }

    /** Waives a penalty; only possible while it is PENDING. */
    @Transactional
    public TicketView waiveAssessedPenalty(UUID supportId, UUID ticketId, UUID penaltyId, String reason) {
        Report ticket = begin(supportId, ticketId, reason);
        PenaltyAssessment penalty = loadPenalty(ticket, penaltyId);
        requirePending(penalty, "be waived");
        penalty.waive(users.getReferenceById(supportId), reason, clock.instant());
        penalties.saveAndFlush(penalty);
        return done(supportId, ticket, "WAIVE_PENALTY_ASSESSED", reason, penaltyData(penalty));
    }

    // ---- refund of the automatic overstay penalty (PARI-100)

    /**
     * Returns the charged automatic overstay penalty to the driver, in full, through the payment ledger (a REFUND payment is
     * added; nothing is overwritten). Allowed only when that penalty was collected (state CHARGED): otherwise 409
     * OVERSTAY_PENALTY_NOT_CHARGED with {@code details.state}, or 409 OVERSTAY_PENALTY_ALREADY_REFUNDED. A held penalty is
     * decided with settle-penalty. Penalties support assessed are not touched. The billed amounts on the reservation stay as
     * they were; the refund is shown in {@code overstayPenalty}. {@code operationId} identifies the request: the same one again
     * returns the ticket unchanged. The reservation row is locked, so two requests at once cannot refund twice. A refused
     * refund is 402 PAYMENT_FAILED and leaves nothing behind.
     */
    @Transactional
    public TicketView refundOverstayPenalty(UUID supportId, UUID ticketId, String reason, String operationId) {
        Report ticket = begin(supportId, ticketId, reason);
        String key = operationKey("refund-overstay-penalty", ticketId, operationId);
        Reservation reservation = lockReservation(ticket);
        if (key.equals(reservation.getOverstayRefundOperationKey())) {
            return TicketView.of(ticket, true);
        }
        OverstayPenaltyView.State state = OverstayPenaltyView.stateOf(reservation);
        if (state == OverstayPenaltyView.State.REFUNDED) {
            throw new ConflictException("OVERSTAY_PENALTY_ALREADY_REFUNDED", "The overstay penalty was already refunded");
        }
        if (state != OverstayPenaltyView.State.CHARGED) {
            throw new ConflictException("OVERSTAY_PENALTY_NOT_CHARGED",
                    "Only a charged overstay penalty can be refunded; this one is " + state,
                    Map.of("state", state.name()));
        }
        Payment charged = OverstayPenaltyView.chargedPayment(reservation).orElseThrow();
        Payment refund = paymentService.refundPayment(charged);
        reservation.recordOverstayRefund(refund, key, clock.instant());
        Map<String, Object> extra = new java.util.HashMap<>();
        extra.put("amountMinor", refund.getAmount().amountMinor());
        extra.put("operationId", operationId.trim());
        extra.put("refundPaymentId", refund.getId().toString());
        extra.put("targetUserId", reservation.getDriver().getId().toString());
        TicketView view = done(supportId, ticket, "REFUND_OVERSTAY_PENALTY", reason, extra);
        events.publishEvent(TicketEvent.of(TicketEvent.Type.OVERSTAY_PENALTY_REFUNDED, ticket, refund.getAmount().toDecimal().toPlainString()));
        return view;
    }

    /** Locks the reservation row and reloads it, so a second request waits and then sees the refund of the first. */
    private Reservation lockReservation(Report ticket) {
        Reservation reservation = (Reservation) Hibernate.unproxy(ticket.getReservation());
        entityManager.refresh(reservation, LockModeType.PESSIMISTIC_WRITE);
        return reservation;
    }

    // ---- itemized evacuation fees (PARI-88)

    /**
     * Adds an evacuation fee to the ticket's reservation on top of the ones already there, and charges it to the driver
     * right away. A refused payment is stored as a FAILED item and does not count towards the total. Needs an evacuation
     * that was requested on this ticket. {@code operationId} makes a retry harmless: the same one returns the ticket
     * unchanged, with no second item and no second payment.
     */
    @Transactional
    public TicketView addEvacuationFee(UUID supportId, UUID ticketId, long amountMinor, String reason, String operationId) {
        Report ticket = begin(supportId, ticketId, reason);
        if (ticket.getEvacuationStatus() == null) {
            throw new IllegalStateTransitionException("This ticket has no evacuation");
        }
        String key = operationKey("evacuation-fee", ticketId, operationId);
        if (evacuationFees.findByOperationKey(key).isPresent()) {
            return TicketView.of(ticket, true);
        }
        EvacuationFee fee = chargeFee(supportId, ticket, Money.pln(amountMinor), reason, key);
        return done(supportId, ticket, "EVACUATION_FEE_ADDED", reason, feeData(fee));
    }

    /**
     * Reverses one charged evacuation fee in full. The fee stays in the list; a REFUND item is added next to it, so the
     * history is kept and the total drops by that amount. A fee can be refunded once (409 EVACUATION_FEE_ALREADY_REFUNDED);
     * a failed fee has nothing to refund. To correct an amount, refund the fee and add the right one.
     */
    @Transactional
    public TicketView refundEvacuationFee(UUID supportId, UUID ticketId, UUID feeId, String reason) {
        Report ticket = begin(supportId, ticketId, reason);
        EvacuationFee fee = evacuationFees.findById(feeId).filter(f -> f.getTicket().getId().equals(ticketId))
                .orElseThrow(() -> new NotFoundException("Evacuation fee not found"));
        if (evacuationFees.existsByRefundsId(feeId)) {
            throw new ConflictException("EVACUATION_FEE_ALREADY_REFUNDED", "This evacuation fee was already refunded");
        }
        if (!fee.isEligibleFee()) {
            throw new IllegalStateTransitionException("Only a charged evacuation fee can be refunded");
        }
        Payment refund = paymentService.refundPayment(fee.getPayment());
        EvacuationFee item = EvacuationFee.refundOf(fee, refund, reason, "evacuation-refund:" + feeId, users.getReferenceById(supportId));
        evacuationFees.saveAndFlush(item);
        ticket.getReservation().registerEvacuationFee(item);
        return done(supportId, ticket, "EVACUATION_FEE_REFUNDED", reason, feeData(item));
    }

    private EvacuationFee chargeFee(UUID supportId, Report ticket, Money amount, String reason, String key) {
        Payment payment = reservationService.chargeDriver(ticket.getReservation(), PaymentType.EVAC_FEE, amount, key);
        EvacuationFee fee = evacuationFees.saveAndFlush(
                EvacuationFee.charged(ticket, amount, payment, reason, key, users.getReferenceById(supportId)));
        ticket.getReservation().registerEvacuationFee(fee);
        return fee;
    }

    private PenaltyAssessment loadPenalty(Report ticket, UUID penaltyId) {
        return penalties.findById(penaltyId).filter(p -> p.getTicket().getId().equals(ticket.getId()))
                .orElseThrow(() -> new NotFoundException("Penalty not found"));
    }

    private static void requirePending(PenaltyAssessment penalty, String action) {
        if (penalty.getState() != PenaltyState.PENDING) {
            throw new IllegalStateTransitionException("Penalty", penalty.getState(), action);
        }
    }

    private static String operationKey(String kind, UUID ticketId, String operationId) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("An operationId is required");
        }
        return kind + ":" + ticketId + ":" + operationId.trim();
    }

    private static Map<String, Object> penaltyData(PenaltyAssessment p) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("penaltyId", p.getId().toString());
        data.put("amountMinor", p.getAmount().amountMinor());
        data.put("targetUserId", p.getTarget().getId().toString());
        data.put("penaltyState", p.getState().name());
        return data;
    }

    private static Map<String, Object> feeData(EvacuationFee f) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("feeId", f.getId().toString());
        data.put("amountMinor", f.getAmount().amountMinor());
        data.put("feeKind", f.getKind().name());
        data.put("feeStatus", f.getStatus().name());
        if (f.getRefunds() != null) {
            data.put("refundsFeeId", f.getRefunds().getId().toString());
        }
        return data;
    }

    /** Audit metadata: who had the ticket before an assignment change (null when nobody). */
    private static Map<String, Object> previousAssignee(Report ticket) {
        Map<String, Object> extra = new java.util.HashMap<>();
        extra.put("previousAssigneeId", ticket.getAssignedTo() == null ? null : ticket.getAssignedTo().getId().toString());
        return extra;
    }

    private Report begin(UUID supportId, UUID ticketId, String reason) {
        ticketService.requireSupport(supportId);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required for every support command");
        }
        Report ticket = ticketService.load(ticketId);
        if (!ticket.isOpen()) {
            throw new pl.pollub.pari.common.exception.ConflictException("TICKET_CLOSED", "The ticket is already closed");
        }
        return ticket;
    }

    private TicketView done(UUID supportId, Report ticket, String command, String reason) {
        return done(supportId, ticket, command, reason, Map.of());
    }

    /**
     * Writes the audit entry of a command that succeeded. A command that fails rolls back and leaves no entry (documented
     * audit policy). {@code extra} carries safe structured metadata such as the previous assignee; the state after the
     * command is always recorded.
     */
    private TicketView done(UUID supportId, Report ticket, String command, String reason, Map<String, Object> extra) {
        Map<String, Object> data = new java.util.LinkedHashMap<>(extra);
        data.put("reason", reason);
        data.put("code", ticket.getReservation().getCode());
        data.put("outcome", "SUCCEEDED");
        data.put("ticketStatus", ticket.getStatus().name());
        data.put("assignedToId", ticket.getAssignedTo() == null ? null : ticket.getAssignedTo().getId().toString());
        if (ticket.getEvacuationStatus() != null) {
            data.put("evacuationStatus", ticket.getEvacuationStatus().name());
        }
        audit.record(supportId, "Ticket", ticket.getId(), "SUPPORT_" + command, data);
        return TicketView.of(ticket, true);
    }
}
