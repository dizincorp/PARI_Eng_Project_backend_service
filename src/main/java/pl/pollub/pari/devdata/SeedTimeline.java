package pl.pollub.pari.devdata;

import pl.pollub.pari.common.domain.TimeRange;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Every date of the scenario seed (PARI-94), calculated from the application clock and the business time zone, never
 * written down as a calendar date. Windows near "now" are offsets from the current minute; windows on other days are
 * local times (Europe/Warsaw) of the day offset from today. Pure, so it can be checked for any date.
 */
public record SeedTimeline(Instant now, ZoneId zone,
                           TimeRange upcoming, TimeRange approaching, TimeRange active, TimeRange overdueAuto,
                           TimeRange overdueFailed, TimeRange overdueTicket, TimeRange doneReviewed, TimeRange doneVnl,
                           TimeRange noShow, TimeRange cancelled, TimeRange expired, TimeRange pending,
                           TimeRange sharedSlot,
                           TimeRange wholeDayBlock, TimeRange partialBlock, TimeRange freeInterval, TimeRange unitBlock,
                           TimeRange closure, TimeRange pendingSpotBlock, TimeRange weekdaySpotBlock,
                           TimeRange earlyMorningBlock) {

    public static SeedTimeline of(Instant clockNow, ZoneId zone) {
        Instant now = clockNow.truncatedTo(ChronoUnit.MINUTES);
        LocalDate today = now.atZone(zone).toLocalDate();
        return new SeedTimeline(now, zone,
                // reservations
                day(today, zone, 1, 9, 11),
                around(now, 30, 150),
                around(now, -40, 80),
                around(now, -300, -180),
                around(now, -360, -240),
                around(now, -165, -45),
                day(today, zone, -1, 10, 12),
                day(today, zone, -1, 14, 16),
                around(now, -300, -180),
                day(today, zone, 4, 10, 12),
                day(today, zone, 3, 10, 12),
                day(today, zone, 2, 10, 12),
                day(today, zone, 1, 9, 11),
                // blocks and a free interval
                day(today, zone, 3, 0, 24),
                day(today, zone, 1, 13, 15),
                day(today, zone, 1, 15, 20),
                day(today, zone, 2, 8, 18),
                day(today, zone, 5, 0, 24),
                day(today, zone, 2, 14, 16),
                day(today, zone, 6, 0, 24),
                day(today, zone, 1, 6, 9));
    }

    /** {@code fromMinute} and {@code toMinute} are offsets from {@code now}. */
    private static TimeRange around(Instant now, long fromMinute, long toMinute) {
        return new TimeRange(now.plus(Duration.ofMinutes(fromMinute)), now.plus(Duration.ofMinutes(toMinute)));
    }

    /** Local hours of the day {@code offset} days from today; hour 24 is the next midnight. */
    private static TimeRange day(LocalDate today, ZoneId zone, int offset, int fromHour, int toHour) {
        LocalDate date = today.plusDays(offset);
        return new TimeRange(at(date, fromHour, zone), at(date, toHour, zone));
    }

    private static Instant at(LocalDate date, int hour, ZoneId zone) {
        return hour >= 24 ? date.plusDays(1).atStartOfDay(zone).toInstant() : date.atTime(hour, 0).atZone(zone).toInstant();
    }
}
