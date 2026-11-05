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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Price of a reservation (F13, F18, PARI-55). Three rental types are priced differently, so a long booking is never
 * billed by counting hours:
 * <ul>
 *   <li>{@code HOURLY}: whole started hours times the hourly price; service fee = max(minimum fee, share of the spot cost).</li>
 *   <li>{@code DAILY}: calendar days (at least one) times the daily rate; fixed service fee.</li>
 *   <li>{@code MONTHLY}: months times the monthly rate, minus the long-term discount; fixed service fee.</li>
 * </ul>
 * A space without its own daily rate uses {@code daily_rate_hourly_multiplier} x the hourly price, one without its own
 * monthly rate {@code monthly_rate_daily_multiplier} x the daily rate. The service fee is added to what the driver pays,
 * the commission is taken from what the owner receives (zero for Premium owners); every rate and fee comes from
 * {@code platform_settings}.
 */
@Service
public class PricingService {

    /**
     * {@code baseCost} is the cost of the rental type before the discount; {@code discountAmount} is the long-term discount
     * ({@code discountPercent} of the base cost); {@code spotCost} = baseCost - discountAmount. {@code total} is what the
     * driver pays: spotCost + serviceFee. The owner gets spotCost - commission, so the discount is carried by the owner.
     * {@code durationDays} is set for DAILY, {@code durationMonths} for MONTHLY bookings.
     */
    public record Quote(Money hourlyRate, long minutes, Money spotCost, Money serviceFee, Money commission, Money total,
                        Money baseCost, BigDecimal discountPercent, Money discountAmount,
                        BookingType bookingType, Integer durationDays, Integer durationMonths) {

        public Quote(Money hourlyRate, long minutes, Money spotCost, Money serviceFee, Money commission, Money total,
                     Money baseCost, BigDecimal discountPercent, Money discountAmount) {
            this(hourlyRate, minutes, spotCost, serviceFee, commission, total, baseCost, discountPercent, discountAmount,
                    BookingType.HOURLY, null, null);
        }

        public Quote(Money hourlyRate, long minutes, Money spotCost, Money serviceFee, Money commission, Money total) {
            this(hourlyRate, minutes, spotCost, serviceFee, commission, total, spotCost, BigDecimal.ZERO, Money.ZERO);
        }
    }

    private static final long MAX_MONTHS = 36;

    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);

    private final PlatformSettings settings;
    private final PremiumSubscriptionRepository subscriptions;
    private final Clock clock;
    private final ZoneId zone;

    public PricingService(PlatformSettings settings, PremiumSubscriptionRepository subscriptions, Clock clock, ZoneId businessZone) {
        this.settings = settings;
        this.subscriptions = subscriptions;
        this.clock = clock;
        this.zone = businessZone;
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
     * for {@code durationMonths} calendar months (1 to 36); without it the number of months is taken from {@code end}
     * (at least one) and {@code end} is replaced by the end of the last month.
     */
    public TimeRange periodFor(BookingType type, Instant start, Instant end, Integer durationMonths) {
        if (start == null) {
            throw new IllegalArgumentException("start is required");
        }
        BookingType t = type == null ? BookingType.HOURLY : type;
        if (t == BookingType.MONTHLY) {
            if (durationMonths == null && end == null) {
                throw new IllegalArgumentException("durationMonths or end is required for a MONTHLY booking");
            }
            int months = monthsOf(start, end, durationMonths);
            if (months < 1 || months > MAX_MONTHS) {
                throw new IllegalArgumentException("durationMonths must be between 1 and " + MAX_MONTHS + " for a MONTHLY booking");
            }
            return new TimeRange(start, start.atOffset(ZoneOffset.UTC).plusMonths(months).toInstant());
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

    /** Price of a booking (see the class comment). {@code durationMonths} is optional for MONTHLY: it is taken from the range. */
    public Quote quote(ParkingSpot spot, TimeRange range, BookingType bookingType, Integer durationMonths) {
        BookingType type = bookingType == null ? BookingType.HOURLY : bookingType;
        Money hourlyRate = spot.getHourlyPrice();
        long minutes = range.duration().toMinutes();
        Money baseCost;
        Money serviceFee;
        BigDecimal discountPercent = BigDecimal.ZERO;
        Integer days = null;
        Integer months = null;
        switch (type) {
            case DAILY -> {
                days = (int) Math.max(1, ChronoUnit.DAYS.between(localDate(range.start()), localDate(range.end())));
                baseCost = dailyRate(spot).multiply(BigDecimal.valueOf(days));
                serviceFee = Money.pln(settings.integer("service_fee_daily_minor"));
            }
            case MONTHLY -> {
                months = monthsOf(range.start(), range.end(), durationMonths);
                baseCost = monthlyRate(spot).multiply(BigDecimal.valueOf(months));
                discountPercent = monthlyDiscountPercent(months);
                serviceFee = Money.pln(settings.integer("service_fee_monthly_minor"));
            }
            default -> {
                long hours = (minutes + 59) / 60;                       // every started hour is paid
                baseCost = hourlyRate.multiply(BigDecimal.valueOf(hours));
                serviceFee = null;                                      // needs the spot cost, below
            }
        }
        Money discountAmount = baseCost.multiply(discountPercent.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        Money spotCost = baseCost.subtract(discountAmount);
        if (serviceFee == null) {
            Money share = spotCost.multiply(settings.decimal("service_fee_rate"));
            Money minimum = Money.pln(settings.integer("service_fee_hourly_min_minor"));
            serviceFee = share.amountMinor() > minimum.amountMinor() ? share : minimum;
        }
        // Owner Premium (F08, PARI-23): no commission while the plan is active at booking time.
        boolean premiumOwner = subscriptions.findCurrentOne(spot.getOwner().getId(), clock.instant()).isPresent();
        Money commission = premiumOwner ? Money.ZERO : spotCost.multiply(settings.decimal("commission_rate"));
        return new Quote(hourlyRate, minutes, spotCost, serviceFee, commission, spotCost.add(serviceFee),
                baseCost, discountPercent, discountAmount, type, days, months);
    }

    /** The owner's daily rate, or the hourly price times {@code daily_rate_hourly_multiplier} when none is set. */
    private Money dailyRate(ParkingSpot spot) {
        return spot.getDailyPrice() != null ? spot.getDailyPrice()
                : spot.getHourlyPrice().multiply(settings.decimal("daily_rate_hourly_multiplier"));
    }

    /** The owner's monthly rate, or the daily rate times {@code monthly_rate_daily_multiplier} when none is set. */
    private Money monthlyRate(ParkingSpot spot) {
        return spot.getMonthlyPrice() != null ? spot.getMonthlyPrice()
                : dailyRate(spot).multiply(settings.decimal("monthly_rate_daily_multiplier"));
    }

    /** {@code durationMonths} when given, otherwise the whole months between start and end (at least one). */
    private int monthsOf(Instant start, Instant end, Integer durationMonths) {
        if (durationMonths != null) {
            return durationMonths;
        }
        return (int) Math.max(1, ChronoUnit.MONTHS.between(ZonedDateTime.ofInstant(start, zone), ZonedDateTime.ofInstant(end, zone)));
    }

    private LocalDate localDate(Instant instant) {
        return ZonedDateTime.ofInstant(instant, zone).toLocalDate();
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
