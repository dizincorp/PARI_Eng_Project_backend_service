package pl.pollub.pari.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import pl.pollub.pari.common.domain.BaseEntity;
import pl.pollub.pari.user.domain.User;

import java.time.Instant;
import java.util.Map;

/** A message for one user on one channel (F07, F16, F25). */
@Entity
@Table(name = "notifications")
@Getter
public class Notification extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel;

    private Instant readAt;

    private Instant sentAt;

    protected Notification() {
    }

    public Notification(User user, String type, String title, String body, Map<String, Object> payload,
                        NotificationChannel channel) {
        this.user = user;
        this.type = type;
        this.title = title;
        this.body = body;
        this.payload = payload;
        this.channel = channel;
    }

    public boolean isRead() {
        return readAt != null;
    }

    public void markRead(Instant now) {
        if (readAt == null) {
            this.readAt = now;
        }
    }

    public void markSent(Instant now) {
        this.sentAt = now;
    }
}
