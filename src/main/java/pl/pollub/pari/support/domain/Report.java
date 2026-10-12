package pl.pollub.pari.support.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import pl.pollub.pari.common.domain.BaseEntity;
import pl.pollub.pari.common.domain.IllegalStateTransitionException;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.user.domain.User;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A problem reported by a driver or an owner about a reservation (scenario S4). */
@Entity
@Table(name = "reports")
@Getter
public class Report extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_user_id")
    private User reportedUser;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReportStatus status = ReportStatus.OPEN;

    /** The support employee handling the report. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private User assignedTo;

    @Column(columnDefinition = "text")
    private String resolution;

    private Instant closedAt;

    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    private List<ReportMessage> messages = new ArrayList<>();

    protected Report() {
    }

    public Report(Reservation reservation, User reporter, User reportedUser, String category, String description) {
        this.reservation = reservation;
        this.reporter = reporter;
        this.reportedUser = reportedUser;
        this.category = category;
        this.description = description;
    }

    public boolean isOpen() {
        return status == ReportStatus.OPEN || status == ReportStatus.IN_PROGRESS;
    }

    public void assignTo(User supportUser) {
        requireOpen("be assigned");
        this.assignedTo = supportUser;
        this.status = ReportStatus.IN_PROGRESS;
    }

    public void resolve(String resolution) {
        requireOpen("be resolved");
        this.resolution = resolution;
        this.status = ReportStatus.RESOLVED;
    }

    public void close(Instant now) {
        if (status == ReportStatus.CLOSED) {
            throw new IllegalStateTransitionException("Report", status, "be closed");
        }
        this.status = ReportStatus.CLOSED;
        this.closedAt = now;
    }

    public ReportMessage addMessage(User author, String body, boolean internalNote) {
        ReportMessage message = new ReportMessage(this, author, body, internalNote);
        messages.add(message);
        return message;
    }

    private void requireOpen(String action) {
        if (!isOpen()) {
            throw new IllegalStateTransitionException("Report", status, action);
        }
    }
}
