package pl.pollub.pari.support.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.common.service.AuditTrail;
import pl.pollub.pari.common.service.PlatformSettings;
import pl.pollub.pari.reservation.service.ReservationService;
import pl.pollub.pari.support.domain.EvacuationStatus;
import pl.pollub.pari.support.domain.Report;
import pl.pollub.pari.support.domain.Sanction;
import pl.pollub.pari.support.domain.SanctionType;
import pl.pollub.pari.support.event.TicketEvent;
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

    public SupportCommandService(SupportTicketService ticketService, ReservationService reservationService, UserRepository users,
                                 ModerationService moderation, AuditTrail audit, PlatformSettings settings, Clock clock,
                                 ApplicationEventPublisher events) {
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
        boolean participant = target.getId().equals(ticket.getReservation().getDriver().getId())
                || target.getId().equals(ticket.getReservation().getSpot().getOwner().getId())
                || (ticket.getReporter() != null && target.getId().equals(ticket.getReporter().getId()));
        if (!participant) {
            throw new pl.pollub.pari.common.exception.BadRequestException("SANCTION_TARGET_NOT_PARTICIPANT",
                    "The user is not a participant of this ticket's reservation");
        }
        moderation.apply(supportId, target, ticket, type, reason);
        java.util.Map<String, Object> extra = new java.util.HashMap<>();
        extra.put("targetUserId", userId.toString());
        extra.put("sanctionType", type.name());
        extra.put("targetBlocked", target.isBlocked());
        return done(supportId, ticket, "SANCTION_" + type.name(), reason, extra);
    }

    /** Hands the ticket to another support employee. */
    @Transactional
    public TicketView reassign(UUID supportId, UUID ticketId, UUID newAssigneeId, String reason) {
        Report ticket = begin(supportId, ticketId, reason);
        UUID previous = ticket.getAssignedTo() == null ? null : ticket.getAssignedTo().getId();
        ticket.reassign(ticketService.requireSupport(newAssigneeId));
        java.util.Map<String, Object> extra = new java.util.HashMap<>();
        extra.put("previousAssigneeId", previous == null ? null : previous.toString());
        return done(supportId, ticket, "REASSIGN", reason, extra);
    }

    /** Releases a claimed ticket to the unassigned queue without closing it or changing its resolution (PARI-83). */
    @Transactional
    public TicketView release(UUID supportId, UUID ticketId, String reason) {
        Report ticket = begin(supportId, ticketId, reason);
        UUID previous = ticket.getAssignedTo() == null ? null : ticket.getAssignedTo().getId();
        ticket.unassign();
        java.util.Map<String, Object> extra = new java.util.HashMap<>();
        extra.put("previousAssigneeId", previous == null ? null : previous.toString());
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
        if (next == EvacuationStatus.DONE && ticket.getEvacuationFee() != null) {
            reservationService.chargeEvacuationFee(ticket.getReservation().getId(), ticket.getEvacuationFee());
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
