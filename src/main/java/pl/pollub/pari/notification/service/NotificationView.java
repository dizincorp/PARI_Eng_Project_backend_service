package pl.pollub.pari.notification.service;

import pl.pollub.pari.notification.domain.Notification;

import java.time.Instant;
import java.util.UUID;

/**
 * A notification as the API shows it (PARI-39). {@code ticketId} and {@code messageId} are set for support tickets and
 * replies (PARI-86) and null otherwise; {@code reservationId} is taken from the payload when there is one, so the client
 * can route to the ticket or the reservation.
 */
public record NotificationView(UUID id, String title, String message, String type, boolean read, Instant createdAt,
                               UUID ticketId, UUID messageId, UUID reservationId) {

    public static NotificationView of(Notification n) {
        return new NotificationView(n.getId(), n.getTitle(), n.getBody(), n.getType(), n.isRead(), n.getCreatedAt(),
                n.getTicketId(), n.getMessageId(), reservationId(n));
    }

    private static UUID reservationId(Notification n) {
        Object value = n.getPayload() == null ? null : n.getPayload().get("reservationId");
        try {
            return value == null ? null : UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
