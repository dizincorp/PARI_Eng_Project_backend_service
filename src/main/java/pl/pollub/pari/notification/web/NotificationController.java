package pl.pollub.pari.notification.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.pollub.pari.auth.security.CurrentUserId;
import pl.pollub.pari.notification.service.NotificationService;
import pl.pollub.pari.notification.service.NotificationView;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** In-app notifications of the caller (PARI-39). */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public List<NotificationView> list(@CurrentUserId UUID userId,
                                       @RequestParam(defaultValue = "false") boolean unreadOnly,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return notifications.list(userId, unreadOnly, Math.max(page, 0), Math.min(Math.max(size, 1), 50));
    }

    /** Reads everything the caller has about one ticket; the response is the number of notifications that changed. */
    @PatchMapping("/tickets/{ticketId}/read")
    public Map<String, Integer> markTicketRead(@CurrentUserId UUID userId, @PathVariable UUID ticketId) {
        return Map.of("updated", notifications.markTicketRead(userId, ticketId));
    }

    @PatchMapping("/{id}/read")
    public NotificationView markRead(@CurrentUserId UUID userId, @PathVariable UUID id) {
        return notifications.markRead(userId, id);
    }
}
