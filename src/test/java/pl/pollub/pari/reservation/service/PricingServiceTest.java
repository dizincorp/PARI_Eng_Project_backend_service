package pl.pollub.pari.reservation.service;

import org.junit.jupiter.api.Test;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.domain.TimeRange;
import pl.pollub.pari.common.service.PlatformSettings;
import pl.pollub.pari.premium.domain.PremiumSubscription;
import pl.pollub.pari.premium.repository.PremiumSubscriptionRepository;
import pl.pollub.pari.reservation.domain.BookingType;
import pl.pollub.pari.spot.domain.Address;
import pl.pollub.pari.spot.domain.Dimensions;
import pl.pollub.pari.spot.domain.GeoPoint;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PricingServiceTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    private final PlatformSettings settings = mock(PlatformSettings.class);
    private final PremiumSubscriptionRepository subscriptions = mock(PremiumSubscriptionRepository.class);
    private final PricingService pricing = new PricingService(settings, subscriptions, Clock.fixed(NOW, ZoneOffset.UTC));

    PricingServiceTest() {
        when(settings.decimal("service_fee_rate")).thenReturn(new BigDecimal("0.05"));
        when(settings.decimal("commission_rate")).thenReturn(new BigDecimal("0.10"));
        when(settings.decimal("overstay_penalty_multiplier")).thenReturn(new BigDecimal("1.50"));
        when(settings.integer("overstay_grace_minutes")).thenReturn(15);
        when(settings.decimal("discount_3_months_percent")).thenReturn(new BigDecimal("5"));
        when(settings.decimal("discount_6_months_percent")).thenReturn(new BigDecimal("10"));
        when(settings.decimal("discount_12_months_percent")).thenReturn(new BigDecimal("15"));
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
    void premiumOwnerPaysNoCommissionButTheDriverFeeStays() {
        ParkingSpot spot = spotAt(1000);
        PremiumSubscription plan = new PremiumSubscription(spot.getOwner(), PremiumSubscription.MONTHLY, NOW.minusSeconds(60),
                NOW.plusSeconds(3600), "sub_1");
        when(subscriptions.findCurrentOne(any(), eq(NOW))).thenReturn(Optional.of(plan));

        PricingService.Quote quote = pricing.quote(spot, range("2030-01-07T10:00:00Z", "2030-01-07T12:00:00Z"));

        assertThat(quote.commission()).isEqualTo(Money.ZERO);
        assertThat(quote.serviceFee()).isEqualTo(Money.pln(100));
        assertThat(quote.total()).isEqualTo(Money.pln(2100));
    }

    @Test
    void overstayIsChargedAtTheMultipliedRateProratedByTheStartedMinute() {
        assertThat(pricing.overstayPenalty(Money.pln(1000), Duration.ofMinutes(60))).isEqualTo(Money.pln(1500));
        assertThat(pricing.overstayPenalty(Money.pln(1000), Duration.ofMinutes(30))).isEqualTo(Money.pln(750));
        // 16 min 1 s count as 17 started minutes: 17/60 h * 10.00 * 1.5 = 4.25
        assertThat(pricing.overstayPenalty(Money.pln(1000), Duration.ofSeconds(16 * 60 + 1))).isEqualTo(Money.pln(425));
    }

    @Test
    void nothingIsChargedWithinTheGracePeriodButTheWholeOverstayOnceItIsExceeded() {
        assertThat(pricing.overstayPenalty(Money.pln(1000), Duration.ofMinutes(15))).isEqualTo(Money.ZERO);
        // 16 minutes: charged from the reserved end, not from the end of the grace: 16/60 h * 10.00 * 1.5 = 4.00
        assertThat(pricing.overstayPenalty(Money.pln(1000), Duration.ofMinutes(16))).isEqualTo(Money.pln(400));
        // the ticket example: 150 min at 12.00/h = 2.5 h * 12.00 * 1.5 = 45.00
        assertThat(pricing.overstayPenalty(Money.pln(1200), Duration.ofMinutes(150))).isEqualTo(Money.pln(4500));
    }

    @Test
    void noOverstayMeansNoPenalty() {
        assertThat(pricing.overstayPenalty(Money.pln(1000), Duration.ZERO)).isEqualTo(Money.ZERO);
        assertThat(pricing.overstayPenalty(Money.pln(1000), Duration.ofMinutes(-5))).isEqualTo(Money.ZERO);
    }

    @Test
    void monthlyBookingsGetTheDiscountOfTheirTier() {
        ParkingSpot spot = spotAt(1000);
        Instant start = Instant.parse("2030-01-01T00:00:00Z");
        // 1 Jan to 1 Apr 2030 = 90 days = 2160 h = 21600.00 PLN
        PricingService.Quote three = quoteMonths(spot, start, 3);

        assertThat(three.baseCost()).isEqualTo(Money.pln(2_160_000));
        assertThat(three.discountPercent()).isEqualByComparingTo("5");
        assertThat(three.discountAmount()).isEqualTo(Money.pln(108_000));
        assertThat(three.spotCost()).isEqualTo(Money.pln(2_052_000));
        assertThat(three.serviceFee()).isEqualTo(Money.pln(102_600)); // 5% of the discounted cost
        assertThat(three.total()).isEqualTo(Money.pln(2_154_600));

        assertThat(quoteMonths(spot, start, 2).discountPercent()).isEqualByComparingTo("0");
        assertThat(quoteMonths(spot, start, 5).discountPercent()).isEqualByComparingTo("5");
        assertThat(quoteMonths(spot, start, 6).discountPercent()).isEqualByComparingTo("10");
        assertThat(quoteMonths(spot, start, 11).discountPercent()).isEqualByComparingTo("10");
        assertThat(quoteMonths(spot, start, 12).discountPercent()).isEqualByComparingTo("15");
    }

    @Test
    void hourlyAndDailyBookingsGetNoDiscount() {
        PricingService.Quote daily = pricing.quote(spotAt(1000), range("2030-01-07T00:00:00Z", "2030-01-09T00:00:00Z"),
                BookingType.DAILY, null);

        assertThat(daily.minutes()).isEqualTo(2880);
        assertThat(daily.discountAmount()).isEqualTo(Money.ZERO);
        assertThat(daily.spotCost()).isEqualTo(daily.baseCost());
    }

    @Test
    void thePeriodDependsOnTheBookingType() {
        Instant start = Instant.parse("2030-01-31T10:00:00Z");

        assertThat(pricing.periodFor(BookingType.MONTHLY, start, null, 1).end()).isEqualTo(Instant.parse("2030-02-28T10:00:00Z"));
        assertThat(pricing.periodFor(BookingType.HOURLY, start, start.plusSeconds(5400), null).duration().toMinutes()).isEqualTo(90);
        assertThat(pricing.periodFor(null, start, start.plusSeconds(3600), null).duration().toMinutes()).isEqualTo(60);
        assertThat(pricing.periodFor(BookingType.DAILY, start, start.plusSeconds(86_400), null).duration().toHours()).isEqualTo(24);
    }

    @Test
    void invalidBookingInputIsRefused() {
        Instant start = Instant.parse("2030-01-07T10:00:00Z");

        assertThatThrownBy(() -> pricing.periodFor(BookingType.MONTHLY, start, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pricing.periodFor(BookingType.MONTHLY, start, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pricing.periodFor(BookingType.MONTHLY, start, null, 37)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pricing.periodFor(BookingType.DAILY, start, start.plusSeconds(90_000), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pricing.periodFor(BookingType.HOURLY, start, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    private PricingService.Quote quoteMonths(ParkingSpot spot, Instant start, int months) {
        return pricing.quote(spot, pricing.periodFor(BookingType.MONTHLY, start, null, months), BookingType.MONTHLY, months);
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
