package pl.pollub.pari.support.event;

import java.util.UUID;

/**
 * A support employee posted a public reply on a ticket (PARI-86). Published only for public replies, never for internal
 * notes, and only when the ticket has a reporter other than the author.
 */
public record TicketReplyEvent(UUID ticketId, UUID messageId, UUID reporterId, UUID authorId, UUID reservationId,
                               String reservationCode, UUID spotId, String spotName) {
}
