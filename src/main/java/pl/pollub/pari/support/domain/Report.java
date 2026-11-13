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
import pl.pollub.pari.common.domain.Money;
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

    /** Null for a ticket opened by the system. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id")
    private User reporter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_user_id")
    private User reportedUser;

    @Column(nullable = false)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketType type = TicketType.OTHER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketSource source = TicketSource.DRIVER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketPriority priority = TicketPriority.NORMAL;

    @Enumerated(EnumType.STRING)
    private EvacuationStatus evacuationStatus;

    private String evacuationDestination;

    @Column(name = "evacuation_fee_minor")
    private Money evacuationFee;

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

    /** A ticket of the support module (PARI-70); the category is the ticket type name. */
    public static Report ticket(Reservation reservation, User reporter, User reportedUser, TicketType type,
                                TicketSource source, String description) {
        Report report = new Report(reservation, reporter, reportedUser, type.name(), description);
        report.type = type;
        report.source = source;
        report.priority = type.defaultPriority();
        return report;
    }

    /** Support picks the ticket from the queue. */
    public void claim(User supportUser) {
        assignTo(supportUser);
    }

    /** Another support employee takes over; allowed while the ticket is open. */
    public void reassign(User supportUser) {
        requireOpen("be reassigned");
        this.assignedTo = supportUser;
        this.status = ReportStatus.IN_PROGRESS;
    }

    /** Releases the ticket back to the shared queue; it stays open and keeps its messages and resolution (PARI-83). */
    public void unassign() {
        requireOpen("be released");
        if (assignedTo == null) {
            throw new IllegalStateTransitionException("The ticket is not assigned");
        }
        this.assignedTo = null;
        this.status = ReportStatus.OPEN;
    }

    /** Support starts moving the car away. {@code fee} is what the driver is charged. */
    public void requestEvacuation(String destination, Money fee) {
        requireOpen("start an evacuation");
        if (evacuationStatus != null) {
            throw new IllegalStateTransitionException("An evacuation was already started for this ticket");
        }
        this.evacuationStatus = EvacuationStatus.REQUESTED;
        this.evacuationDestination = destination;
        this.evacuationFee = fee;
    }

    /** REQUESTED to IN_PROGRESS to DONE, in this order only. */
    public void advanceEvacuation(EvacuationStatus next) {
        boolean ok = (evacuationStatus == EvacuationStatus.REQUESTED && next == EvacuationStatus.IN_PROGRESS)
                || (evacuationStatus == EvacuationStatus.IN_PROGRESS && next == EvacuationStatus.DONE);
        if (!ok) {
            throw new IllegalStateTransitionException("Evacuation cannot go from " + evacuationStatus + " to " + next);
        }
        this.evacuationStatus = next;
    }

    /** Support adjusts the evacuation fee, for example to waive it. */
    public void adjustEvacuationFee(Money fee) {
        if (evacuationStatus == null) {
            throw new IllegalStateTransitionException("This ticket has no evacuation");
        }
        if (evacuationStatus == EvacuationStatus.DONE) {
            throw new IllegalStateTransitionException(
                    "The evacuation is done and its fee was charged; refund that fee and add a new one instead of changing it");
        }
        this.evacuationFee = fee;
    }

    /** True for the driver and the owner of the ticket's reservation and for the reporter. */
    public boolean involves(java.util.UUID userId) {
        return (reporter != null && reporter.getId().equals(userId))
                || reservation.getDriver().getId().equals(userId)
                || reservation.getSpot().getOwner().getId().equals(userId);
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
