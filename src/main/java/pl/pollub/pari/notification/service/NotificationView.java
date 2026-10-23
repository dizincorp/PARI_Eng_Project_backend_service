package pl.pollub.pari.notification.service;

import pl.pollub.pari.notification.domain.Notification;

import java.time.Instant;
import java.util.UUID;

/** A notification as the API shows it (PARI-39). */
public record NotificationView(UUID id, String title, String message, String type, boolean read, Instant createdAt) {

    public static NotificationView of(Notification n) {
        return new NotificationView(n.getId(), n.getTitle(), n.getBody(), n.getType(), n.isRead(), n.getCreatedAt());
    }
}
