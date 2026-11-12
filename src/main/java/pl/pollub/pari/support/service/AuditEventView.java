package pl.pollub.pari.support.service;

import pl.pollub.pari.common.domain.AuditLogEntry;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One line of the audit trail of a ticket (PARI-84), kept apart from the conversation messages and internal notes.
 * {@code action} is the command or event (for example SUPPORT_FORCE_CHECK_OUT or TICKET_CLAIMED). Only successful
 * commands are recorded: a command that fails rolls back and leaves no entry, so {@code outcome} is SUCCEEDED.
 * {@code metadata} holds safe structured values (state after the command, previous assignee), never payment or personal data.
 */
public record AuditEventView(long id, UUID ticketId, String action, UUID actorId, String actorName, Instant createdAt,
                             String reason, String outcome, Map<String, Object> metadata) {

    /** The page of events, oldest first. */
    public record Page(List<AuditEventView> items, int page, int size, long total) {
    }

    private static final java.util.Set<String> LIFTED = java.util.Set.of("reason", "outcome");

    static AuditEventView of(AuditLogEntry e, String actorName) {
        Map<String, Object> data = e.getData() == null ? Map.of() : e.getData();
        Map<String, Object> metadata = new LinkedHashMap<>();
        data.forEach((k, v) -> {
            if (!LIFTED.contains(k)) {
                metadata.put(k, v);
            }
        });
        Object reason = data.get("reason");
        Object outcome = data.get("outcome");
        return new AuditEventView(e.getId(), e.getEntityId(), e.getAction(), e.getActorId(), actorName, e.getCreatedAt(),
                reason == null ? null : reason.toString(), outcome == null ? "SUCCEEDED" : outcome.toString(), metadata);
    }
}
