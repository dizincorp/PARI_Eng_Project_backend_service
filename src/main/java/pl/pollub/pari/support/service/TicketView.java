package pl.pollub.pari.support.service;

import pl.pollub.pari.support.domain.EvacuationStatus;
import pl.pollub.pari.support.domain.Report;
import pl.pollub.pari.support.domain.ReportStatus;
import pl.pollub.pari.support.domain.TicketPriority;
import pl.pollub.pari.support.domain.TicketSource;
import pl.pollub.pari.support.domain.TicketType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read model of a support ticket. Internal notes are included only when the viewer is support. */
public record TicketView(UUID id, TicketType type, TicketSource source, TicketPriority priority, ReportStatus status,
                         UUID reservationId, String reservationCode, UUID reporterId, UUID assignedToId, String description,
                         String resolution, EvacuationStatus evacuationStatus, String evacuationDestination,
                         BigDecimal evacuationFee, Instant createdAt, Instant closedAt, List<MessageView> messages) {

    public record MessageView(UUID id, UUID authorId, String body, boolean internalNote, Instant createdAt) {
    }

    public static TicketView of(Report t, boolean viewerIsSupport) {
        List<MessageView> messages = t.getMessages().stream()
                .filter(m -> viewerIsSupport || !m.isInternalNote())
                .map(m -> new MessageView(m.getId(), m.getAuthor().getId(), m.getBody(), m.isInternalNote(), m.getCreatedAt()))
                .toList();
        return new TicketView(t.getId(), t.getType(), t.getSource(), t.getPriority(), t.getStatus(),
                t.getReservation().getId(), t.getReservation().getCode(),
                t.getReporter() == null ? null : t.getReporter().getId(),
                t.getAssignedTo() == null ? null : t.getAssignedTo().getId(), t.getDescription(), t.getResolution(),
                t.getEvacuationStatus(), t.getEvacuationDestination(),
                t.getEvacuationFee() == null ? null : t.getEvacuationFee().toDecimal(), t.getCreatedAt(), t.getClosedAt(), messages);
    }
}
