package pl.pollub.pari.support.domain;

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
import pl.pollub.pari.user.domain.User;

/** History of warnings, blocks and unblocks of a user (F22). */
@Entity
@Table(name = "sanctions")
@Getter
public class Sanction extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id")
    private Report report;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "issued_by", nullable = false)
    private User issuedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SanctionType type;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    protected Sanction() {
    }

    public Sanction(User user, Report report, User issuedBy, SanctionType type, String reason) {
        this.user = user;
        this.report = report;
        this.issuedBy = issuedBy;
        this.type = type;
        this.reason = reason;
    }
}
