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
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The values of the Jira ticket PARI-55: fees 2.50 / 2.50 / 5.00, a day = 7 hours and a month = 20 days when no rate is set. */
class PricingServiceTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    private final PlatformSettings settings = mock(PlatformSettings.class);
    private final PremiumSubscriptionRepository subscriptions = mock(PremiumSubscriptionRepository.class);
    private final PricingService pricing = new PricingService(settings, subscriptions, Clock.fixed(NOW, ZoneOffset.UTC),
            ZoneId.of("Europe/Warsaw"));

    PricingServiceTest() {
        when(settings.decimal("service_fee_rate")).thenReturn(new BigDecimal("0.05"));
        when(settings.integer("service_fee_hourly_min_minor")).thenReturn(250);
        when(settings.integer("service_fee_daily_minor")).thenReturn(250);
        when(settings.integer("service_fee_monthly_minor")).thenReturn(500);
        when(settings.decimal("daily_rate_hourly_multiplier")).thenReturn(new BigDecimal("7"));
        when(settings.decimal("monthly_rate_daily_multiplier")).thenReturn(new BigDecimal("20"));
        when(settings.decimal("commission_rate")).thenReturn(new BigDecimal("0.10"));
        when(settings.decimal("overstay_penalty_multiplier")).thenReturn(new BigDecimal("1.50"));
        when(settings.integer("overstay_grace_minutes")).thenReturn(15);
        when(settings.decimal("discount_3_months_percent")).thenReturn(new BigDecimal("5"));
        when(settings.decimal("discount_6_months_percent")).thenReturn(new BigDecimal("10"));
        when(settings.decimal("discount_12_months_percent")).thenReturn(new BigDecimal("15"));
    }

    // ------------------------------------------------------------------ HOURLY

    @Test
    void wholeHoursCostHourlyRateTimesHoursAndThePayerPaysAtLeastTheMinimumFee() {
        PricingService.Quote quote = pricing.quote(spotAt(1000), range("2030-01-07T10:00:00Z", "2030-01-07T12:00:00Z"));

        assertThat(quote.minutes()).isEqualTo(120);
        assertThat(quote.bookingType()).isEqualTo(BookingType.HOURLY);
        assertThat(quote.spotCost()).isEqualTo(Money.pln(2000));
        assertThat(quote.serviceFee()).isEqualTo(Money.pln(250));         // 5% of 20.00 is 1.00: the 2.50 minimum applies
        assertThat(quote.commission()).isEqualTo(Money.pln(200));
        assertThat(quote.total()).isEqualTo(Money.pln(2250));
    }

    @Test
    void everyStartedHourIsPaid() {
        PricingService.Quote ninety = pricing.quote(spotAt(1000), range("2030-01-07T10:00:00Z", "2030-01-07T11:30:00Z"));
        PricingService.Quote seven = pricing.quote(spotAt(1000), range("2030-01-07T10:00:00Z", "2030-01-07T10:07:00Z"));
        PricingService.Quote exactly = pricing.quote(spotAt(1000), range("2030-01-07T10:00:00Z", "2030-01-07T11:00:00Z"));

        assertThat(ninety.spotCost()).isEqualTo(Money.pln(2000));         // 1.5 h is billed as 2 h
        assertThat(seven.spotCost()).isEqualTo(Money.pln(1000));          // 7 min is billed as 1 h
        assertThat(exactly.spotCost()).isEqualTo(Money.pln(1000));
        assertThat(ninety.minutes()).isEqualTo(90);                        // the real duration stays visible
    }

    @Test
    void theHourlyServiceFeeIsTheShareOfTheSpotCostWhenItIsAboveTheMinimum() {
        // 10 h at 100.00 = 1000.00; 5% = 50.00 > 2.50; commission 10% = 100.00
        PricingService.Quote quote = pricing.quote(spotAt(10_000), range("2030-01-07T08:00:00Z", "2030-01-07T18:00:00Z"));

        assertThat(quote.spotCost()).isEqualTo(Money.pln(100_000));
        assertThat(quote.serviceFee()).isEqualTo(Money.pln(5_000));
        assertThat(quote.commission()).isEqualTo(Money.pln(10_000));
        assertThat(quote.total()).isEqualTo(Money.pln(105_000));
    }

    @Test
    void premiumOwnerPaysNoCommissionButTheDriverFeeStays() {
        ParkingSpot spot = spotAt(1000);
        PremiumSubscription plan = new PremiumSubscription(spot.getOwner(), PremiumSubscription.MONTHLY, NOW.minusSeconds(60),
                NOW.plusSeconds(3600), "sub_1");
        when(subscriptions.findCurrentOne(any(), eq(NOW))).thenReturn(Optional.of(plan));

        PricingService.Quote quote = pricing.quote(spot, range("2030-01-07T10:00:00Z", "2030-01-07T12:00:00Z"));

        assertThat(quote.commission()).isEqualTo(Money.ZERO);
        assertThat(quote.serviceFee()).isEqualTo(Money.pln(250));
        assertThat(quote.total()).isEqualTo(Money.pln(2250));
    }

    @Test
    void anHourlyBookingIgnoresTheDailyAndMonthlyRates() {
        ParkingSpot spot = spotAt(1000);
        spot.setDailyPrice(Money.pln(500));
        spot.setMonthlyPrice(Money.pln(500));

        PricingService.Quote quote = pricing.quote(spot, range("2030-01-07T10:00:00Z", "2030-01-07T12:00:00Z"));

        assertThat(quote.baseCost()).isEqualTo(Money.pln(2_000));
    }

    // ------------------------------------------------------------------ DAILY

    @Test
    void aDailyBookingCostsTheOwnersDailyRateForEveryDayPlusAFixedFee() {
        ParkingSpot spot = spotAt(1000);
        spot.setDailyPrice(Money.pln(20_000));                 // the owner asks 200.00 per day

        PricingService.Quote quote = pricing.quote(spot, range("2030-01-07T00:00:00Z", "2030-01-09T00:00:00Z"), BookingType.DAILY, null);

        assertThat(quote.bookingType()).isEqualTo(BookingType.DAILY);
        assertThat(quote.durationDays()).isEqualTo(2);
        assertThat(quote.baseCost()).isEqualTo(Money.pln(40_000));
        assertThat(quote.discountAmount()).isEqualTo(Money.ZERO);
        assertThat(quote.spotCost()).isEqualTo(Money.pln(40_000));
        assertThat(quote.serviceFee()).isEqualTo(Money.pln(250));          // fixed 2.50, not a share
        assertThat(quote.commission()).isEqualTo(Money.pln(4_000));
        assertThat(quote.total()).isEqualTo(Money.pln(40_250));
        assertThat(quote.hourlyRate()).isEqualTo(Money.pln(1000));
    }

    @Test
    void aDayWithoutTheOwnersRateCostsSevenHours() {
        // 7 days at 7 x 15.00 = 105.00 per day, not 168 hours x 15.00
        PricingService.Quote quote = pricing.quote(spotAt(1500), range("2030-01-07T00:00:00Z", "2030-01-14T00:00:00Z"), BookingType.DAILY, null);

        assertThat(quote.durationDays()).isEqualTo(7);
        assertThat(quote.baseCost()).isEqualTo(Money.pln(73_500));
        assertThat(quote.total()).isEqualTo(Money.pln(73_750));
    }

    @Test
    void aSevenDayBookingCostsSevenTimesTheDailyRatePlusTheFee() {
        ParkingSpot spot = spotAt(1500);
        spot.setDailyPrice(Money.pln(9_000));

        PricingService.Quote quote = pricing.quote(spot, range("2030-01-07T00:00:00Z", "2030-01-14T00:00:00Z"), BookingType.DAILY, null);

        assertThat(quote.spotCost()).isEqualTo(Money.pln(63_000));
        assertThat(quote.total()).isEqualTo(Money.pln(63_250));
    }

    // ------------------------------------------------------------------ MONTHLY

    @Test
    void aTwelveMonthBookingIsBilledByTheMonthlyRateNotByTheHour() {
        // the ticket: 15.00/h, 450.00 per month, 12 months: 5400.00 - 15% = 4590.00 + 5.00 fee = 4595.00 (not 117 274.50)
        ParkingSpot spot = spotAt(1500);
        spot.setMonthlyPrice(Money.pln(45_000));

        PricingService.Quote quote = quoteMonths(spot, Instant.parse("2030-01-01T00:00:00Z"), 12);

        assertThat(quote.bookingType()).isEqualTo(BookingType.MONTHLY);
        assertThat(quote.durationMonths()).isEqualTo(12);
        assertThat(quote.baseCost()).isEqualTo(Money.pln(540_000));
        assertThat(quote.discountPercent()).isEqualByComparingTo("15");
        assertThat(quote.discountAmount()).isEqualTo(Money.pln(81_000));
        assertThat(quote.spotCost()).isEqualTo(Money.pln(459_000));
        assertThat(quote.serviceFee()).isEqualTo(Money.pln(500));          // fixed 5.00
        assertThat(quote.total()).isEqualTo(Money.pln(459_500));
    }

    @Test
    void aMonthWithoutTheOwnersRatesCostsTwentyDays() {
        // 10.00/h -> 70.00 per day -> 1400.00 per month; 3 months = 4200.00, 5% off = 3990.00
        PricingService.Quote quote = quoteMonths(spotAt(1000), Instant.parse("2030-01-01T00:00:00Z"), 3);

        assertThat(quote.baseCost()).isEqualTo(Money.pln(420_000));
        assertThat(quote.discountAmount()).isEqualTo(Money.pln(21_000));
        assertThat(quote.spotCost()).isEqualTo(Money.pln(399_000));
        assertThat(quote.total()).isEqualTo(Money.pln(399_500));
    }

    @Test
    void aMonthWithOnlyADailyRateCostsTwentyDailyRates() {
        ParkingSpot spot = spotAt(1000);
        spot.setDailyPrice(Money.pln(10_000));                 // 100.00 per day -> 2000.00 per month

        PricingService.Quote quote = quoteMonths(spot, Instant.parse("2030-01-01T00:00:00Z"), 1);

        assertThat(quote.baseCost()).isEqualTo(Money.pln(200_000));
    }

    @Test
    void monthlyBookingsGetTheDiscountOfTheirTier() {
        ParkingSpot spot = spotAt(1000);
        spot.setMonthlyPrice(Money.pln(100_000));
        Instant start = Instant.parse("2030-01-01T00:00:00Z");

        assertThat(quoteMonths(spot, start, 2).discountPercent()).isEqualByComparingTo("0");
        assertThat(quoteMonths(spot, start, 3).discountPercent()).isEqualByComparingTo("5");
        assertThat(quoteMonths(spot, start, 5).discountPercent()).isEqualByComparingTo("5");
        assertThat(quoteMonths(spot, start, 6).discountPercent()).isEqualByComparingTo("10");
        assertThat(quoteMonths(spot, start, 11).discountPercent()).isEqualByComparingTo("10");
        assertThat(quoteMonths(spot, start, 12).discountPercent()).isEqualByComparingTo("15");
        assertThat(quoteMonths(spot, start, 2).spotCost()).isEqualTo(Money.pln(200_000));
    }

    @Test
    void theNumberOfMonthsIsTakenFromTheDatesWhenNoneIsGiven() {
        ParkingSpot spot = spotAt(1000);
        spot.setMonthlyPrice(Money.pln(100_000));
        TimeRange threeMonths = range("2030-01-01T00:00:00Z", "2030-04-01T00:00:00Z");

        PricingService.Quote quote = pricing.quote(spot, threeMonths, BookingType.MONTHLY, null);

        assertThat(quote.durationMonths()).isEqualTo(3);
        assertThat(quote.baseCost()).isEqualTo(Money.pln(300_000));
        assertThat(pricing.quote(spot, range("2030-01-01T00:00:00Z", "2030-01-10T00:00:00Z"), BookingType.MONTHLY, null).durationMonths())
                .isEqualTo(1);                                // a part of a month is at least one month
    }

    // ------------------------------------------------------------------ periods and input

    @Test
    void thePeriodDependsOnTheBookingType() {
        Instant start = Instant.parse("2030-01-31T10:00:00Z");

        assertThat(pricing.periodFor(BookingType.MONTHLY, start, null, 1).end()).isEqualTo(Instant.parse("2030-02-28T10:00:00Z"));
        assertThat(pricing.periodFor(BookingType.MONTHLY, Instant.parse("2030-01-15T10:00:00Z"), Instant.parse("2030-04-15T10:00:00Z"), null).end())
                .isEqualTo(Instant.parse("2030-04-15T10:00:00Z"));          // 3 months counted from the dates
        assertThat(pricing.periodFor(BookingType.MONTHLY, start, Instant.parse("2031-01-01T00:00:00Z"), 2).end())
                .isEqualTo(Instant.parse("2030-03-31T10:00:00Z"));          // durationMonths wins over end
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

    // ------------------------------------------------------------------ overstay (unchanged)

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
