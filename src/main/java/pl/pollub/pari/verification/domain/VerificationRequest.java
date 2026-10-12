package pl.pollub.pari.verification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import pl.pollub.pari.common.domain.BaseEntity;
import pl.pollub.pari.common.domain.IllegalStateTransitionException;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.user.domain.User;

import java.time.Instant;

/** An owner's request for an offline inspection of a space and its outcome (F10, F23 to F25). */
@Entity
@Table(name = "verification_requests")
@Getter
public class VerificationRequest extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "spot_id", nullable = false)
    private ParkingSpot spot;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by", nullable = false)
    private User requestedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verifier_id")
    private User verifier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VerificationStatus status = VerificationStatus.REQUESTED;

    @Column(columnDefinition = "text")
    private String rejectionReason;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(nullable = false, updatable = false)
    private Instant requestedAt;

    private Instant decidedAt;

    protected VerificationRequest() {
    }

    public VerificationRequest(ParkingSpot spot, User requestedBy, Instant now) {
        this.spot = spot;
        this.requestedBy = requestedBy;
        this.requestedAt = now;
    }

    /** A verifier takes the request from the queue. */
    public void claim(User verifier) {
        require("be claimed", VerificationStatus.REQUESTED);
        this.verifier = verifier;
        this.status = VerificationStatus.ASSIGNED;
    }

    /** Approval gives the space the "Verified" badge (F24). */
    public void approve(String notes, Instant now) {
        require("be approved", VerificationStatus.ASSIGNED);
        this.status = VerificationStatus.APPROVED;
        this.notes = notes;
        this.decidedAt = now;
        spot.markVerified();
    }

    /** A rejection needs a reason; the owner is told by e-mail (F25). */
    public void reject(String reason, Instant now) {
        require("be rejected", VerificationStatus.ASSIGNED);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a rejection reason is required");
        }
        this.status = VerificationStatus.REJECTED;
        this.rejectionReason = reason;
        this.decidedAt = now;
    }

    private void require(String action, VerificationStatus expected) {
        if (status != expected) {
            throw new IllegalStateTransitionException("VerificationRequest", status, action);
        }
    }
}
