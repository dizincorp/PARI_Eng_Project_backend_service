package pl.pollub.pari.availability.domain;

import org.junit.jupiter.api.Test;
import pl.pollub.pari.common.domain.TimeRange;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvailabilityRuleTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    /** 2030-01-07 is a Monday; Warsaw is UTC+1 in January. Monday 08:00-20:00 local. */
    private final AvailabilityRule monday = new AvailabilityRule(null, 1, LocalTime.of(8, 0), LocalTime.of(20, 0), null, null);

    private static TimeRange utc(String start, String end) {
        return new TimeRange(Instant.parse(start), Instant.parse(end));
    }

    @Test
    void coversARangeInsideTheWindow() {
        assertThat(monday.covers(utc("2030-01-07T09:00:00Z", "2030-01-07T11:00:00Z"), WARSAW)).isTrue();   // 10:00-12:00 local
    }

    @Test
    void doesNotCoverARangeThatEndsAfterTheWindow() {
        assertThat(monday.covers(utc("2030-01-07T18:00:00Z", "2030-01-07T20:00:00Z"), WARSAW)).isFalse();  // 19:00-21:00 local
    }

    @Test
    void doesNotCoverAnotherWeekday() {
        assertThat(monday.covers(utc("2030-01-08T09:00:00Z", "2030-01-08T11:00:00Z"), WARSAW)).isFalse();
    }

    @Test
    void usesLocalTimeAcrossDaylightSaving() {
        // 2030-07-01 is a Monday; Warsaw is UTC+2 in summer: 07:00Z-09:00Z is 09:00-11:00 local
        assertThat(monday.covers(utc("2030-07-01T07:00:00Z", "2030-07-01T09:00:00Z"), WARSAW)).isTrue();
        // 05:00Z-07:00Z is 07:00-09:00 local, starts before 08:00
        assertThat(monday.covers(utc("2030-07-01T05:00:00Z", "2030-07-01T07:00:00Z"), WARSAW)).isFalse();
    }

    @Test
    void respectsTheValidityPeriod() {
        AvailabilityRule limited = new AvailabilityRule(null, 1, LocalTime.of(8, 0), LocalTime.of(20, 0),
                LocalDate.of(2030, 1, 14), LocalDate.of(2030, 1, 28));

        assertThat(limited.covers(utc("2030-01-07T09:00:00Z", "2030-01-07T11:00:00Z"), WARSAW)).isFalse();
        assertThat(limited.covers(utc("2030-01-14T09:00:00Z", "2030-01-14T11:00:00Z"), WARSAW)).isTrue();
        assertThat(limited.covers(utc("2030-02-04T09:00:00Z", "2030-02-04T11:00:00Z"), WARSAW)).isFalse();
    }

    @Test
    void aRangeEndingAtMidnightCountsAsTheEndOfTheDay() {
        AvailabilityRule allDay = new AvailabilityRule(null, 1, LocalTime.MIN, LocalTime.MAX, null, null);

        assertThat(allDay.covers(utc("2030-01-07T20:00:00Z", "2030-01-07T23:00:00Z"), WARSAW)).isTrue();  // 21:00-24:00 local
    }

    @Test
    void rejectsInvalidRules() {
        assertThatThrownBy(() -> new AvailabilityRule(null, 8, LocalTime.of(8, 0), LocalTime.of(9, 0), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AvailabilityRule(null, 1, LocalTime.of(9, 0), LocalTime.of(9, 0), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
