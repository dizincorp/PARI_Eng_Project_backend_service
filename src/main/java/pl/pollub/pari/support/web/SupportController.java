package pl.pollub.pari.support.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.pollub.pari.auth.security.CurrentUserId;
import pl.pollub.pari.support.domain.EvacuationStatus;
import pl.pollub.pari.support.domain.ReportStatus;
import pl.pollub.pari.support.domain.SanctionType;
import pl.pollub.pari.support.domain.TicketPriority;
import pl.pollub.pari.support.domain.TicketType;
import pl.pollub.pari.support.service.SupportCommandService;
import pl.pollub.pari.support.service.AuditEventView;
import pl.pollub.pari.support.service.ModerationService;
import pl.pollub.pari.support.service.StaffView;
import pl.pollub.pari.support.service.SupportReservationView;
import pl.pollub.pari.support.service.SupportTicketService;
import pl.pollub.pari.support.service.TicketView;

import java.util.List;
import java.util.UUID;

/** The support page API (PARI-70, PARI-71, PARI-72). The SUPPORT or ADMIN role is checked in the services (403 otherwise). */
@RestController
@RequestMapping("/api/support")
public class SupportController {

    /** Every support command carries a reason; it is audited. */
    public record ReasonRequest(@NotBlank String reason) {
    }

    public record SettlePenaltyRequest(@NotNull Boolean waive, @NotBlank String reason) {
    }

    public record SanctionRequest(@NotNull UUID userId, @NotNull SanctionType type, @NotBlank String reason) {
    }

    public record ReassignRequest(@NotNull UUID assigneeId, @NotBlank String reason) {
    }

    public record EvacuationRequest(@NotBlank String destination, @NotBlank String reason) {
    }

    public record EvacuationStatusRequest(@NotNull EvacuationStatus status, @NotBlank String reason) {
    }

    public record EvacuationFeeRequest(long feeMinor, @NotBlank String reason) {
    }

    /** {@code operationId} identifies the request: a retry with the same id changes nothing. {@code targetUserId} defaults to the driver. */
    public record AssessPenaltyRequest(@jakarta.validation.constraints.Positive long amountMinor, UUID targetUserId,
                                       @NotBlank String reason,
                                       @NotBlank @jakarta.validation.constraints.Size(max = 100) String operationId) {
    }

    public record EvacuationFeeItemRequest(@jakarta.validation.constraints.Positive long amountMinor, @NotBlank String reason,
                                           @NotBlank @jakarta.validation.constraints.Size(max = 100) String operationId) {
    }

    public record NoteRequest(@NotBlank String body, boolean internalNote) {
    }

    private final SupportTicketService tickets;
    private final SupportCommandService commands;
    private final ModerationService moderation;

    public SupportController(SupportTicketService tickets, SupportCommandService commands, ModerationService moderation) {
        this.tickets = tickets;
        this.commands = commands;
        this.moderation = moderation;
    }

    /** Accounts that are blocked now (SUPPORT or ADMIN only), with the block reason and the warnings that count. */
    @GetMapping("/users/blocked")
    public List<ModerationService.BlockedUserView> blockedUsers(@CurrentUserId UUID userId,
                                                                @RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
        return moderation.blockedUsers(userId, Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    /** Blocked or not, warnings that count now and the whole sanction history of an account (SUPPORT or ADMIN only). */
    @GetMapping("/users/{userId}/moderation")
    public ModerationService.ModerationView moderation(@CurrentUserId UUID actorId, @PathVariable UUID userId) {
        return moderation.moderation(actorId, userId);
    }

    /** Unblocks an account without a ticket. A reason is required; earlier sanctions are kept, 409 when it is not blocked. */
    @PostMapping("/users/{userId}/unblock")
    public ModerationService.ModerationView unblock(@CurrentUserId UUID actorId, @PathVariable UUID userId,
                                                    @Valid @RequestBody ReasonRequest r) {
        return moderation.unblock(actorId, userId, r.reason());
    }

    /** The shared queue, high priority first, then the oldest. */
    @GetMapping("/tickets")
    public List<TicketView> queue(@CurrentUserId UUID userId,
                                  @RequestParam(required = false) ReportStatus status,
                                  @RequestParam(required = false) TicketType type,
                                  @RequestParam(required = false) TicketPriority priority,
                                  @RequestParam(defaultValue = "false") boolean unassigned,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return tickets.queue(userId, status, type, priority, unassigned, page, Math.min(Math.max(size, 1), 100));
    }

    /** Support agents and administrators who can be assigned a ticket (SUPPORT or ADMIN only). */
    @GetMapping("/tickets/staff")
    public List<StaffView> staff(@CurrentUserId UUID userId) {
        return tickets.staff(userId);
    }

    /**
     * The reservation linked to a ticket: spot, interval with time zone, vehicle plate, driver and owner (ids, names, e-mail,
     * phone), check-in and check-out, amounts, penalty and refund state. No PIN, QR token or other access secret.
     * SUPPORT or ADMIN only; 404 for an unknown ticket.
     */
    @GetMapping("/tickets/{id}/reservation")
    public SupportReservationView reservation(@CurrentUserId UUID userId, @PathVariable UUID id) {
        return tickets.reservationOf(userId, id);
    }

    /**
     * The audit timeline of a ticket, oldest first, paginated (default 50 per page, at most 200). It holds claims,
     * reassignments, releases, penalty decisions, cancellations, no-show corrections, force check-outs, sanctions and
     * evacuation changes. Only successful commands are recorded; a failed one leaves no entry. SUPPORT or ADMIN only.
     */
    @GetMapping("/tickets/{id}/audit")
    public AuditEventView.Page audit(@CurrentUserId UUID userId, @PathVariable UUID id,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int size) {
        return tickets.auditTrail(userId, id, Math.max(page, 0), Math.min(Math.max(size, 1), 200));
    }

    @PostMapping("/tickets/{id}/claim")
    public TicketView claim(@CurrentUserId UUID userId, @PathVariable UUID id) {
        return tickets.claim(userId, id);
    }

    @PostMapping("/tickets/{id}/messages")
    public TicketView message(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody NoteRequest r) {
        return tickets.addMessage(userId, id, r.body(), r.internalNote());
    }

    @PostMapping("/tickets/{id}/commands/force-check-out")
    public TicketView forceCheckOut(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        return commands.forceCheckOut(userId, id, r.reason());
    }

    @PostMapping("/tickets/{id}/commands/settle-penalty")
    public TicketView settlePenalty(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody SettlePenaltyRequest r) {
        return commands.settlePenalty(userId, id, r.waive(), r.reason());
    }

    @PostMapping("/tickets/{id}/commands/cancel-with-refund")
    public TicketView cancelWithRefund(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        return commands.cancelWithRefund(userId, id, r.reason());
    }

    @PostMapping("/tickets/{id}/commands/correct-no-show")
    public TicketView correctNoShow(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        return commands.correctNoShow(userId, id, r.reason());
    }

    @PostMapping("/tickets/{id}/commands/sanction")
    public TicketView sanction(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody SanctionRequest r) {
        return commands.sanction(userId, id, r.userId(), r.type(), r.reason());
    }

    /** Returns a claimed ticket to the unassigned queue; it stays open. A reason is required. */
    @PostMapping("/tickets/{id}/commands/release")
    public TicketView release(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        return commands.release(userId, id, r.reason());
    }

    @PostMapping("/tickets/{id}/commands/reassign")
    public TicketView reassign(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody ReassignRequest r) {
        return commands.reassign(userId, id, r.assigneeId(), r.reason());
    }

    /** Resolves and closes the ticket; the reason is the resolution. */
    @PostMapping("/tickets/{id}/commands/close")
    public TicketView close(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        return commands.close(userId, id, r.reason());
    }

    @PostMapping("/tickets/{id}/evacuation")
    public TicketView requestEvacuation(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody EvacuationRequest r) {
        return commands.requestEvacuation(userId, id, r.destination(), r.reason());
    }

    @PostMapping("/tickets/{id}/evacuation/status")
    public TicketView advanceEvacuation(@CurrentUserId UUID userId, @PathVariable UUID id,
                                        @Valid @RequestBody EvacuationStatusRequest r) {
        return commands.advanceEvacuation(userId, id, r.status(), r.reason());
    }

    @PostMapping("/tickets/{id}/evacuation/fee")
    public TicketView adjustEvacuationFee(@CurrentUserId UUID userId, @PathVariable UUID id,
                                          @Valid @RequestBody EvacuationFeeRequest r) {
        return commands.adjustEvacuationFee(userId, id, r.feeMinor(), r.reason());
    }

    /** Assesses a PENDING penalty on the ticket's reservation (PARI-87). Nothing is charged until it is charged here. */
    @PostMapping("/tickets/{id}/penalties")
    public TicketView assessPenalty(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody AssessPenaltyRequest r) {
        return commands.assessPenalty(userId, id, r.amountMinor(), r.targetUserId(), r.reason(), r.operationId());
    }

    @PostMapping("/tickets/{id}/penalties/{penaltyId}/charge")
    public TicketView chargePenalty(@CurrentUserId UUID userId, @PathVariable UUID id, @PathVariable UUID penaltyId,
                                    @Valid @RequestBody ReasonRequest r) {
        return commands.chargePenalty(userId, id, penaltyId, r.reason());
    }

    @PostMapping("/tickets/{id}/penalties/{penaltyId}/waive")
    public TicketView waivePenalty(@CurrentUserId UUID userId, @PathVariable UUID id, @PathVariable UUID penaltyId,
                                   @Valid @RequestBody ReasonRequest r) {
        return commands.waiveAssessedPenalty(userId, id, penaltyId, r.reason());
    }

    /** Adds and charges one more evacuation fee (PARI-88); the earlier ones are kept. */
    @PostMapping("/tickets/{id}/evacuation/fees")
    public TicketView addEvacuationFee(@CurrentUserId UUID userId, @PathVariable UUID id,
                                       @Valid @RequestBody EvacuationFeeItemRequest r) {
        return commands.addEvacuationFee(userId, id, r.amountMinor(), r.reason(), r.operationId());
    }

    /** Reverses one charged evacuation fee in full; it stays in the list next to its REFUND item. */
    @PostMapping("/tickets/{id}/evacuation/fees/{feeId}/refund")
    public TicketView refundEvacuationFee(@CurrentUserId UUID userId, @PathVariable UUID id, @PathVariable UUID feeId,
                                          @Valid @RequestBody ReasonRequest r) {
        return commands.refundEvacuationFee(userId, id, feeId, r.reason());
    }
}
