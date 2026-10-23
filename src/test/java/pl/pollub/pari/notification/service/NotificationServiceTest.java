package pl.pollub.pari.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.notification.domain.Notification;
import pl.pollub.pari.notification.domain.NotificationChannel;
import pl.pollub.pari.support.ServiceTestBase;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PARI-39: a user sees and changes only their own notifications. */
class NotificationServiceTest extends ServiceTestBase {

    @Autowired
    NotificationService service;

    User me;
    User other;
    Notification mine;
    Notification theirs;

    @BeforeEach
    void setUp() {
        me = persistUser(Role.DRIVER);
        other = persistUser(Role.DRIVER);
        mine = notification(me, "RESERVATION_CONFIRMED");
        theirs = notification(other, "RESERVATION_CANCELLED");
        em.flush();
    }

    @Test
    void listShowsOnlyTheCallersNotifications() {
        assertThat(service.list(me.getId(), false, 0, 20)).extracting(NotificationView::id).containsExactly(mine.getId());
    }

    @Test
    void markingReadHidesItFromTheUnreadList() {
        Notification second = notification(me, "DRIVER_CHECKED_IN");
        em.flush();

        NotificationView read = service.markRead(me.getId(), mine.getId());

        assertThat(read.read()).isTrue();
        assertThat(service.list(me.getId(), true, 0, 20)).extracting(NotificationView::id).containsExactly(second.getId());
        assertThat(service.list(me.getId(), false, 0, 20)).hasSize(2);
    }

    @Test
    void anotherUsersNotificationCannotBeMarkedRead() {
        assertThatThrownBy(() -> service.markRead(me.getId(), theirs.getId())).isInstanceOf(NotFoundException.class);
        assertThat(theirs.isRead()).isFalse();
    }

    private Notification notification(User user, String type) {
        Notification n = new Notification(user, type, "Title", "Body", Map.of(), NotificationChannel.IN_APP);
        em.persist(n);
        return n;
    }
}
