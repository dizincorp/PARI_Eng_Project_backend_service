package pl.pollub.pari.common.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeRangeTest {

    private static final Instant T10 = Instant.parse("2030-01-07T10:00:00Z");
    private static final Instant T12 = Instant.parse("2030-01-07T12:00:00Z");
    private static final Instant T14 = Instant.parse("2030-01-07T14:00:00Z");

    @Test
    void endMustBeAfterStart() {
        assertThatThrownBy(() -> new TimeRange(T12, T10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeRange(T12, T12)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adjacentRangesDoNotOverlap() {
        assertThat(new TimeRange(T10, T12).overlaps(new TimeRange(T12, T14))).isFalse();
    }

    @Test
    void overlappingRangesAreDetectedInBothDirections() {
        TimeRange a = new TimeRange(T10, T14);
        TimeRange b = new TimeRange(T12, T14);
        assertThat(a.overlaps(b)).isTrue();
        assertThat(b.overlaps(a)).isTrue();
    }

    @Test
    void containsIsHalfOpen() {
        TimeRange range = new TimeRange(T10, T12);
        assertThat(range.contains(T10)).isTrue();
        assertThat(range.contains(T12)).isFalse();
    }

    @Test
    void computesDuration() {
        assertThat(new TimeRange(T10, T14).duration()).isEqualTo(Duration.ofHours(4));
    }
}
