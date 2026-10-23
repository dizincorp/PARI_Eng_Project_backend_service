package pl.pollub.pari.notification.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.notification.domain.Notification;
import pl.pollub.pari.notification.domain.NotificationChannel;
import pl.pollub.pari.notification.repository.NotificationRepository;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** A user's in-app notifications (PARI-39). A user can only see and change their own. */
@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final Clock clock;

    public NotificationService(NotificationRepository notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    /** Newest first. */
    @Transactional(readOnly = true)
    public List<NotificationView> list(UUID userId, boolean unreadOnly, int page, int size) {
        PageRequest paging = PageRequest.of(page, size);
        var result = unreadOnly
                ? notifications.findByUserIdAndChannelAndReadAtIsNullOrderByCreatedAtDesc(userId, NotificationChannel.IN_APP, paging)
                : notifications.findByUserIdAndChannelOrderByCreatedAtDesc(userId, NotificationChannel.IN_APP, paging);
        return result.map(NotificationView::of).getContent();
    }

    /** 404 for a missing notification and for one that belongs to somebody else. */
    @Transactional
    public NotificationView markRead(UUID userId, UUID notificationId) {
        Notification n = notifications.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new NotFoundException("Notification not found"));
        n.markRead(clock.instant());
        return NotificationView.of(n);
    }
}
