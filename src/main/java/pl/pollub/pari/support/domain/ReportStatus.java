package pl.pollub.pari.support.domain;

import java.util.List;

public enum ReportStatus {
    OPEN,
    IN_PROGRESS,
    RESOLVED,
    CLOSED;

    /** Statuses of a ticket that still needs work. */
    public static final List<ReportStatus> OPEN_STATUSES = List.of(OPEN, IN_PROGRESS);
}
