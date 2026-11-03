package pl.pollub.pari.reservation.service;

import org.springframework.stereotype.Service;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.domain.TimeRange;
import pl.pollub.pari.common.service.PlatformSettings;
import pl.pollub.pari.premium.repository.PremiumSubscriptionRepository;
import pl.pollub.pari.reservation.domain.BookingType;
import pl.pollub.pari.spot.domain.ParkingSpot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Price of a reservation (F13, F18). The spot cost is the hourly rate prorated by the minute. The service fee is added to
 * what the driver pays, the commission is taken from what the owner receives (zero for Premium owners); both rates come from {@code platform_settings}.
 */
@Service
public class PricingService {

    /**
     * {@code baseCost} is the hourly rate times the duration; {@code discountAmount} is the long-term discount
     * ({@code discountPercent} of the base cost); {@code spotCost} = baseCost - discountAmount. {@code total} is what the
     * driver pays: spotCost + serviceFee. The owner gets spotCost - commission, so the discount is carried by the owner.
     */
    public record Quote(Money hourlyRate, long minutes, Money spotCost, Money serviceFee, Money commission, Money total,
                        Money baseCost, BigDecimal discountPercent, Money discountAmount) {

        public Quote(Money hourlyRate, long minutes, Money spotCost, Money serviceFee, Money commission, Money total) {
            this(hourlyRate, minutes, spotCost, serviceFee, commission, total, spotCost, BigDecimal.ZERO, Money.ZERO);
        }
    }

    private static final long MAX_MONTHS = 36;

    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);
    private static final long MINUTES_PER_DAY = 24 * 60;

    private final PlatformSettings settings;
    private final PremiumSubscriptionRepository subscriptions;
    private final Clock clock;

    public PricingService(PlatformSettings settings, PremiumSubscriptionRepository subscriptions, Clock clock) {
        this.settings = settings;
        this.subscriptions = subscriptions;
        this.clock = clock;
    }

    /**
     * Fee for staying past the reserved end (F19, PARI-37): the hourly rate times {@code overstay_penalty_multiplier},
     * prorated by the started minute. Up to {@code overstay_grace_minutes} over the end nothing is charged; once the
     * grace is exceeded the whole overstay (counted from the reserved end, not from the end of the grace) is charged.
     */
    public Money overstayPenalty(Money hourlyRate, Duration overstay) {
        if (overstay.isNegative() || overstay.isZero()
                || overstay.compareTo(Duration.ofMinutes(settings.integer("overstay_grace_minutes"))) <= 0) {
            return Money.ZERO;
        }
        long startedMinutes = (overstay.toSeconds() + 59) / 60;
        BigDecimal hours = BigDecimal.valueOf(startedMinutes).divide(MINUTES_PER_HOUR, 10, RoundingMode.HALF_UP);
        return hourlyRate.multiply(settings.decimal("overstay_penalty_multiplier").multiply(hours));
    }

    /**
     * The period a booking covers. HOURLY: start to end. DAILY: start to end, which must be whole days. MONTHLY: from start
     * for {@code durationMonths} calendar months (1 to 36); {@code end} is ignored.
     */
    public TimeRange periodFor(BookingType type, Instant start, Instant end, Integer durationMonths) {
        if (start == null) {
            throw new IllegalArgumentException("start is required");
        }
        BookingType t = type == null ? BookingType.HOURLY : type;
        if (t == BookingType.MONTHLY) {
            if (durationMonths == null || durationMonths < 1 || durationMonths > MAX_MONTHS) {
                throw new IllegalArgumentException("durationMonths must be between 1 and " + MAX_MONTHS + " for a MONTHLY booking");
            }
            return new TimeRange(start, start.atOffset(ZoneOffset.UTC).plusMonths(durationMonths).toInstant());
        }
        if (end == null) {
            throw new IllegalArgumentException("end is required");
        }
        TimeRange range = new TimeRange(start, end);
        if (t == BookingType.DAILY && range.duration().toMinutes() % (24 * 60) != 0) {
            throw new IllegalArgumentException("a DAILY booking must cover whole days");
        }
        return range;
    }

    public Quote quote(ParkingSpot spot, TimeRange range) {
        return quote(spot, range, BookingType.HOURLY, null);
    }

    /** Price of a booking; the monthly discount applies to {@code MONTHLY} bookings only. */
    public Quote quote(ParkingSpot spot, TimeRange range, BookingType type, Integer durationMonths) {
        Money hourlyRate = spot.getHourlyPrice();
        long minutes = range.duration().toMinutes();
        Money baseCost = baseCost(spot, hourlyRate, minutes, type, durationMonths);
        BigDecimal discountPercent = type == BookingType.MONTHLY ? monthlyDiscountPercent(durationMonths) : BigDecimal.ZERO;
        Money discountAmount = baseCost.multiply(discountPercent.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        Money spotCost = baseCost.subtract(discountAmount);
        Money serviceFee = spotCost.multiply(settings.decimal("service_fee_rate"));
        // Owner Premium (F08, PARI-23): no commission while the plan is active at booking time.
        boolean premiumOwner = subscriptions.findCurrentOne(spot.getOwner().getId(), clock.instant()).isPresent();
        Money commission = premiumOwner ? Money.ZERO : spotCost.multiply(settings.decimal("commission_rate"));
        return new Quote(hourlyRate, minutes, spotCost, serviceFee, commission, spotCost.add(serviceFee),
                baseCost, discountPercent, discountAmount);
    }

    /**
     * Cost before any discount (PARI-55). A {@code DAILY} booking costs the owner's daily price for every whole day, a
     * {@code MONTHLY} one the owner's monthly price for every month booked; when the owner did not set that price (or the
     * booking is hourly) the hourly price times the duration is used, prorated by the minute.
     */
    private Money baseCost(ParkingSpot spot, Money hourlyRate, long minutes, BookingType type, Integer durationMonths) {
        if (type == BookingType.DAILY && spot.getDailyPrice() != null) {
            return spot.getDailyPrice().multiply(BigDecimal.valueOf(minutes / MINUTES_PER_DAY));
        }
        if (type == BookingType.MONTHLY && spot.getMonthlyPrice() != null) {
            return spot.getMonthlyPrice().multiply(BigDecimal.valueOf(durationMonths));
        }
        BigDecimal hours = BigDecimal.valueOf(minutes).divide(MINUTES_PER_HOUR, 10, RoundingMode.HALF_UP);
        return hourlyRate.multiply(hours);
    }

    /** 12 months or more, 6 to 11 months, 3 to 5 months; fewer than 3 months get no discount. Percentages come from platform_settings. */
    BigDecimal monthlyDiscountPercent(Integer months) {
        if (months == null || months < 3) {
            return BigDecimal.ZERO;
        }
        String key = months >= 12 ? "discount_12_months_percent" : months >= 6 ? "discount_6_months_percent" : "discount_3_months_percent";
        return settings.decimal(key);
    }
}
