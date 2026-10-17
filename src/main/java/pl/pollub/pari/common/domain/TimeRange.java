package pl.pollub.pari.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.time.Duration;
import java.time.Instant;

/** Half-open time interval [start, end). Stored as the columns starts_at and ends_at. */
@Embeddable
public record TimeRange(
        @Column(name = "starts_at", nullable = false) Instant start,
        @Column(name = "ends_at", nullable = false) Instant end) {

    public TimeRange {
        if (start != null && end != null && !end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }
    }

    public boolean overlaps(TimeRange other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }

    public boolean contains(Instant instant) {
        return !instant.isBefore(start) && instant.isBefore(end);
    }

    public Duration duration() {
        return Duration.between(start, end);
    }
}
