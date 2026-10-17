package pl.pollub.pari.availability.service;

import org.junit.jupiter.api.Test;
import pl.pollub.pari.availability.domain.AvailabilityException;
import pl.pollub.pari.availability.domain.AvailabilityRule;
import pl.pollub.pari.availability.domain.ExceptionKind;
import pl.pollub.pari.availability.repository.AvailabilityExceptionRepository;
import pl.pollub.pari.availability.repository.AvailabilityRuleRepository;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.domain.TimeRange;
import pl.pollub.pari.reservation.repository.ReservationRepository;
import pl.pollub.pari.spot.domain.Address;
import pl.pollub.pari.spot.domain.Dimensions;
import pl.pollub.pari.spot.domain.GeoPoint;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.spot.repository.ParkingSpotRepository;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 2030-01-07 is a Monday. The business zone is Europe/Warsaw (UTC+1 in January). */
class AvailabilityServiceTest {

    private final AvailabilityRuleRepository rules = mock(AvailabilityRuleRepository.class);
    private final AvailabilityExceptionRepository exceptions = mock(AvailabilityExceptionRepository.class);
    private final AvailabilityService availability = new AvailabilityService(rules, exceptions,
            mock(ParkingSpotRepository.class), mock(ReservationRepository.class), ZoneId.of("Europe/Warsaw"));
    private final ParkingSpot spot = spot();

    @Test
    void aSpaceWithoutRulesIsOpenAllTheTime() {
        when(rules.findBySpotIdOrderByDayOfWeekAscStartTimeAsc(any())).thenReturn(List.of());

        assertThat(availability.isAvailable(spot, range("2030-01-07T02:00:00Z", "2030-01-07T23:00:00Z"))).isTrue();
    }

    @Test
    void aBlockedPeriodInsideTheRangeAlwaysWins() {
        when(exceptions.existsOverlapping(any(), eq(ExceptionKind.BLOCKED), any(), any())).thenReturn(true);
        when(rules.findBySpotIdOrderByDayOfWeekAscStartTimeAsc(any())).thenReturn(List.of());

        assertThat(availability.isAvailable(spot, range("2030-01-07T10:00:00Z", "2030-01-07T12:00:00Z"))).isFalse();
    }

    @Test
    void theRangeMustLieInsideAWeeklyRule() {
        mondayRule("08:00", "18:00");

        assertThat(availability.isAvailable(spot, range("2030-01-07T10:00:00Z", "2030-01-07T12:00:00Z"))).isTrue();  // 11-13 local
        assertThat(availability.isAvailable(spot, range("2030-01-07T16:30:00Z", "2030-01-07T17:30:00Z"))).isFalse(); // 17:30-18:30 local
        assertThat(availability.isAvailable(spot, range("2030-01-08T10:00:00Z", "2030-01-08T12:00:00Z"))).isFalse(); // Tuesday
    }

    @Test
    void anOpenExceptionExtendsTheWeeklySchedule() {
        mondayRule("08:00", "18:00");
        TimeRange evening = range("2030-01-07T16:00:00Z", "2030-01-07T20:00:00Z");
        when(exceptions.findOverlapping(any(), any(), any()))
                .thenReturn(List.of(new AvailabilityException(spot, evening, ExceptionKind.OPEN, null)));

        assertThat(availability.isAvailable(spot, range("2030-01-07T16:30:00Z", "2030-01-07T17:30:00Z"))).isTrue();
    }

    @Test
    void aRangeOverMidnightNeedsRulesForBothDays() {
        when(rules.findBySpotIdOrderByDayOfWeekAscStartTimeAsc(any())).thenReturn(List.of(
                new AvailabilityRule(spot, 1, LocalTime.of(8, 0), LocalTime.MAX, null, null)));
        TimeRange overnight = range("2030-01-07T10:00:00Z", "2030-01-08T08:00:00Z"); // Mon 11:00 -> Tue 09:00 local

        assertThat(availability.isAvailable(spot, overnight)).isFalse();

        when(rules.findBySpotIdOrderByDayOfWeekAscStartTimeAsc(any())).thenReturn(List.of(
                new AvailabilityRule(spot, 1, LocalTime.of(8, 0), LocalTime.MAX, null, null),
                new AvailabilityRule(spot, 2, LocalTime.MIDNIGHT, LocalTime.of(18, 0), null, null)));

        assertThat(availability.isAvailable(spot, overnight)).isTrue();
    }

    private void mondayRule(String from, String to) {
        when(rules.findBySpotIdOrderByDayOfWeekAscStartTimeAsc(any())).thenReturn(List.of(
                new AvailabilityRule(spot, 1, LocalTime.parse(from), LocalTime.parse(to), null, null)));
    }

    private static TimeRange range(String start, String end) {
        return new TimeRange(Instant.parse(start), Instant.parse(end));
    }

    private static ParkingSpot spot() {
        User owner = new User("owner@example.com", "hash", "Olga", "Owner", null, Set.of(Role.OWNER));
        return new ParkingSpot(owner, "Spot", null, new Address("Street 1", "Lublin", "20-001", "PL"),
                new GeoPoint(51.2465, 22.5684), new Dimensions(500, 250, null), Money.pln(1000));
    }
}
