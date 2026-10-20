package pl.pollub.pari.reservation.service;

import org.junit.jupiter.api.Test;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.domain.TimeRange;
import pl.pollub.pari.common.service.PlatformSettings;
import pl.pollub.pari.spot.domain.Address;
import pl.pollub.pari.spot.domain.Dimensions;
import pl.pollub.pari.spot.domain.GeoPoint;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PricingServiceTest {

    private final PlatformSettings settings = mock(PlatformSettings.class);
    private final PricingService pricing = new PricingService(settings);

    PricingServiceTest() {
        when(settings.decimal("service_fee_rate")).thenReturn(new BigDecimal("0.05"));
        when(settings.decimal("commission_rate")).thenReturn(new BigDecimal("0.10"));
        when(settings.decimal("overstay_penalty_multiplier")).thenReturn(new BigDecimal("1.50"));
    }

    @Test
    void wholeHoursCostHourlyRateTimesHours() {
        PricingService.Quote quote = pricing.quote(spotAt(1000), range("2030-01-07T10:00:00Z", "2030-01-07T12:00:00Z"));

        assertThat(quote.minutes()).isEqualTo(120);
        assertThat(quote.spotCost()).isEqualTo(Money.pln(2000));
        assertThat(quote.serviceFee()).isEqualTo(Money.pln(100));
        assertThat(quote.commission()).isEqualTo(Money.pln(200));
        assertThat(quote.total()).isEqualTo(Money.pln(2100));
    }

    @Test
    void partialHoursAreProratedByTheMinute() {
        PricingService.Quote quote = pricing.quote(spotAt(1000), range("2030-01-07T10:00:00Z", "2030-01-07T11:30:00Z"));

        assertThat(quote.spotCost()).isEqualTo(Money.pln(1500));
        assertThat(quote.serviceFee()).isEqualTo(Money.pln(75));
        assertThat(quote.total()).isEqualTo(Money.pln(1575));
    }

    @Test
    void feesAreRoundedHalfUpToWholeGrosze() {
        // 7 minutes at 1000/h = 116.67 -> 117; fee 5% of 117 = 5.85 -> 6; commission 10% = 11.7 -> 12
        PricingService.Quote quote = pricing.quote(spotAt(1000), range("2030-01-07T10:00:00Z", "2030-01-07T10:07:00Z"));

        assertThat(quote.spotCost()).isEqualTo(Money.pln(117));
        assertThat(quote.serviceFee()).isEqualTo(Money.pln(6));
        assertThat(quote.commission()).isEqualTo(Money.pln(12));
        assertThat(quote.total()).isEqualTo(Money.pln(123));
    }

    @Test
    void overstayIsChargedAtTheMultipliedRateProratedByTheStartedMinute() {
        assertThat(pricing.overstayPenalty(Money.pln(1000), Duration.ofMinutes(60))).isEqualTo(Money.pln(1500));
        assertThat(pricing.overstayPenalty(Money.pln(1000), Duration.ofMinutes(30))).isEqualTo(Money.pln(750));
        // 61 seconds count as two started minutes: 2/60 h * 10.00 * 1.5 = 0.50
        assertThat(pricing.overstayPenalty(Money.pln(1000), Duration.ofSeconds(61))).isEqualTo(Money.pln(50));
    }

    @Test
    void noOverstayMeansNoPenalty() {
        assertThat(pricing.overstayPenalty(Money.pln(1000), Duration.ZERO)).isEqualTo(Money.ZERO);
        assertThat(pricing.overstayPenalty(Money.pln(1000), Duration.ofMinutes(-5))).isEqualTo(Money.ZERO);
    }

    private static TimeRange range(String start, String end) {
        return new TimeRange(Instant.parse(start), Instant.parse(end));
    }

    private static ParkingSpot spotAt(long hourlyMinor) {
        User owner = new User("owner@example.com", "hash", "Olga", "Owner", null, Set.of(Role.OWNER));
        return new ParkingSpot(owner, "Spot", null, new Address("Street 1", "Lublin", "20-001", "PL"),
                new GeoPoint(51.2465, 22.5684), new Dimensions(500, 250, null), Money.pln(hourlyMinor));
    }
}
