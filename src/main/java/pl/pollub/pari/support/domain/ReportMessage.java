package pl.pollub.pari.support.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import pl.pollub.pari.common.domain.BaseEntity;
import pl.pollub.pari.user.domain.User;

/** One message in a report thread. Internal notes are visible to support only. */
@Entity
@Table(name = "report_messages")
@Getter
public class ReportMessage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private Report report;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    private boolean internalNote;

    protected ReportMessage() {
    }

    ReportMessage(Report report, User author, String body, boolean internalNote) {
        this.report = report;
        this.author = author;
        this.body = body;
        this.internalNote = internalNote;
    }
}
