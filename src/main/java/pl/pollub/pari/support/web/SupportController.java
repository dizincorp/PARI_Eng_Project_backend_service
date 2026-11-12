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
import pl.pollub.pari.support.service.SupportTicketService;
import pl.pollub.pari.support.service.TicketView;

import java.util.List;
import java.util.UUID;

/** The support page API (PARI-70, PARI-71, PARI-72). The SUPPORT or ADMIN role is checked in the services (403 otherwise). */
@RestController
@RequestMapping("/api/support/tickets")
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

    public record NoteRequest(@NotBlank String body, boolean internalNote) {
    }

    private final SupportTicketService tickets;
    private final SupportCommandService commands;

    public SupportController(SupportTicketService tickets, SupportCommandService commands) {
        this.tickets = tickets;
        this.commands = commands;
    }

    /** The shared queue, high priority first, then the oldest. */
    @GetMapping
    public List<TicketView> queue(@CurrentUserId UUID userId,
                                  @RequestParam(required = false) ReportStatus status,
                                  @RequestParam(required = false) TicketType type,
                                  @RequestParam(required = false) TicketPriority priority,
                                  @RequestParam(defaultValue = "false") boolean unassigned,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return tickets.queue(userId, status, type, priority, unassigned, page, Math.min(Math.max(size, 1), 100));
    }

    @PostMapping("/{id}/claim")
    public TicketView claim(@CurrentUserId UUID userId, @PathVariable UUID id) {
        return tickets.claim(userId, id);
    }

    @PostMapping("/{id}/messages")
    public TicketView message(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody NoteRequest r) {
        return tickets.addMessage(userId, id, r.body(), r.internalNote());
    }

    @PostMapping("/{id}/commands/force-check-out")
    public TicketView forceCheckOut(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        return commands.forceCheckOut(userId, id, r.reason());
    }

    @PostMapping("/{id}/commands/settle-penalty")
    public TicketView settlePenalty(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody SettlePenaltyRequest r) {
        return commands.settlePenalty(userId, id, r.waive(), r.reason());
    }

    @PostMapping("/{id}/commands/cancel-with-refund")
    public TicketView cancelWithRefund(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        return commands.cancelWithRefund(userId, id, r.reason());
    }

    @PostMapping("/{id}/commands/correct-no-show")
    public TicketView correctNoShow(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        return commands.correctNoShow(userId, id, r.reason());
    }

    @PostMapping("/{id}/commands/sanction")
    public TicketView sanction(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody SanctionRequest r) {
        return commands.sanction(userId, id, r.userId(), r.type(), r.reason());
    }

    @PostMapping("/{id}/commands/reassign")
    public TicketView reassign(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody ReassignRequest r) {
        return commands.reassign(userId, id, r.assigneeId(), r.reason());
    }

    /** Resolves and closes the ticket; the reason is the resolution. */
    @PostMapping("/{id}/commands/close")
    public TicketView close(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        return commands.close(userId, id, r.reason());
    }

    @PostMapping("/{id}/evacuation")
    public TicketView requestEvacuation(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody EvacuationRequest r) {
        return commands.requestEvacuation(userId, id, r.destination(), r.reason());
    }

    @PostMapping("/{id}/evacuation/status")
    public TicketView advanceEvacuation(@CurrentUserId UUID userId, @PathVariable UUID id,
                                        @Valid @RequestBody EvacuationStatusRequest r) {
        return commands.advanceEvacuation(userId, id, r.status(), r.reason());
    }

    @PostMapping("/{id}/evacuation/fee")
    public TicketView adjustEvacuationFee(@CurrentUserId UUID userId, @PathVariable UUID id,
                                          @Valid @RequestBody EvacuationFeeRequest r) {
        return commands.adjustEvacuationFee(userId, id, r.feeMinor(), r.reason());
    }
}
