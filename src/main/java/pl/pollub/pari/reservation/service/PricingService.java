package pl.pollub.pari.reservation.service;

import org.springframework.stereotype.Service;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.domain.TimeRange;
import pl.pollub.pari.common.service.PlatformSettings;
import pl.pollub.pari.premium.repository.PremiumSubscriptionRepository;
import pl.pollub.pari.spot.domain.ParkingSpot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;

/**
 * Price of a reservation (F13, F18). The spot cost is the hourly rate prorated by the minute. The service fee is added to
 * what the driver pays, the commission is taken from what the owner receives (zero for Premium owners); both rates come from {@code platform_settings}.
 */
@Service
public class PricingService {

    /** {@code total} is what the driver pays: spotCost + serviceFee. The owner gets spotCost - commission. */
    public record Quote(Money hourlyRate, long minutes, Money spotCost, Money serviceFee, Money commission, Money total) {
    }

    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);

    private final PlatformSettings settings;
    private final PremiumSubscriptionRepository subscriptions;
    private final Clock clock;

    public PricingService(PlatformSettings settings, PremiumSubscriptionRepository subscriptions, Clock clock) {
        this.settings = settings;
        this.subscriptions = subscriptions;
        this.clock = clock;
    }

    /**
     * Fee for staying past the reserved end (F19): the hourly rate times {@code overstay_penalty_multiplier}, prorated by
     * the started minute.
     */
    public Money overstayPenalty(Money hourlyRate, Duration overstay) {
        if (overstay.isNegative() || overstay.isZero()) {
            return Money.ZERO;
        }
        long startedMinutes = (overstay.toSeconds() + 59) / 60;
        BigDecimal hours = BigDecimal.valueOf(startedMinutes).divide(MINUTES_PER_HOUR, 10, RoundingMode.HALF_UP);
        return hourlyRate.multiply(settings.decimal("overstay_penalty_multiplier").multiply(hours));
    }

    public Quote quote(ParkingSpot spot, TimeRange range) {
        Money hourlyRate = spot.getHourlyPrice();
        long minutes = range.duration().toMinutes();
        BigDecimal hours = BigDecimal.valueOf(minutes).divide(MINUTES_PER_HOUR, 10, RoundingMode.HALF_UP);
        Money spotCost = hourlyRate.multiply(hours);
        Money serviceFee = spotCost.multiply(settings.decimal("service_fee_rate"));
        // Owner Premium (F08, PARI-23): no commission while the plan is active at booking time.
        boolean premiumOwner = subscriptions.findCurrentOne(spot.getOwner().getId(), clock.instant()).isPresent();
        Money commission = premiumOwner ? Money.ZERO : spotCost.multiply(settings.decimal("commission_rate"));
        return new Quote(hourlyRate, minutes, spotCost, serviceFee, commission, spotCost.add(serviceFee));
    }
}
