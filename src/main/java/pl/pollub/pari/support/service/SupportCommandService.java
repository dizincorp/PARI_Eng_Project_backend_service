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
    private final SanctionRepository sanctions;
    private final AuditTrail audit;
    private final PlatformSettings settings;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public SupportCommandService(SupportTicketService ticketService, ReservationService reservationService, UserRepository users,
                                 SanctionRepository sanctions, AuditTrail audit, PlatformSettings settings, Clock clock,
                                 ApplicationEventPublisher events) {
        this.ticketService = ticketService;
        this.reservationService = reservationService;
        this.users = users;
        this.sanctions = sanctions;
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
     * Warns the user (type WARNING) or blocks the account (BLOCK). Reaching {@code warning_limit} warnings blocks the
     * account automatically (F22).
     */
    @Transactional
    public TicketView sanction(UUID supportId, UUID ticketId, UUID userId, SanctionType type, String reason) {
        Report ticket = begin(supportId, ticketId, reason);
        User support = users.getReferenceById(supportId);
        User target = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        sanctions.save(new Sanction(target, ticket, support, type, reason));
        switch (type) {
            case BLOCK -> target.block();
            case UNBLOCK -> target.unblock();
            case WARNING -> {
                if (sanctions.countByUserIdAndType(userId, SanctionType.WARNING) >= settings.integer("warning_limit")) {
                    target.block();
                }
            }
        }
        return done(supportId, ticket, "SANCTION_" + type.name(), reason);
    }

    /** Hands the ticket to another support employee. */
    @Transactional
    public TicketView reassign(UUID supportId, UUID ticketId, UUID newAssigneeId, String reason) {
        Report ticket = begin(supportId, ticketId, reason);
        ticket.reassign(ticketService.requireSupport(newAssigneeId));
        return done(supportId, ticket, "REASSIGN", reason);
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
        audit.record(supportId, "Ticket", ticket.getId(), "SUPPORT_" + command,
                Map.of("reason", reason, "code", ticket.getReservation().getCode()));
        return TicketView.of(ticket, true);
    }
}
