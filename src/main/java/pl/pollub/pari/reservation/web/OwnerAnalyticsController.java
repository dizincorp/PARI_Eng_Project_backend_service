package pl.pollub.pari.reservation.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.pollub.pari.auth.security.CurrentUserId;
import pl.pollub.pari.reservation.service.OwnerAnalyticsService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Revenue statistics for the owner dashboard (PARI-24). */
@RestController
@RequestMapping("/api/owner/analytics")
public class OwnerAnalyticsController {

    private static final String[] DAY_LABELS = {"Pon", "Wt", "Śr", "Czw", "Pt", "Sob", "Niedz"};

    public record DayResponse(String day, LocalDate date, BigDecimal amount) {
    }

    /** {@code totalEarnings} is net (after commission); {@code earningsChangePercent} is null when the previous period earned nothing. */
    public record AnalyticsResponse(BigDecimal totalEarnings, BigDecimal grossEarnings, BigDecimal earningsChangePercent,
                                    BigDecimal occupancyRatePercent, int activeSpotsCount, int totalSpotsCount,
                                    BigDecimal averageHourlyRate, String currency, List<DayResponse> dailyBreakdown) {
    }

    private final OwnerAnalyticsService analytics;

    public OwnerAnalyticsController(OwnerAnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping
    public AnalyticsResponse analytics(@CurrentUserId UUID userId, @RequestParam(defaultValue = "7d") String period) {
        OwnerAnalyticsService.Analytics a = analytics.analytics(userId, OwnerAnalyticsService.Period.parse(period));
        List<DayResponse> days = a.dailyBreakdown().stream()
                .map(d -> new DayResponse(DAY_LABELS[d.date().getDayOfWeek().getValue() - 1], d.date(), d.amount().toDecimal()))
                .toList();
        return new AnalyticsResponse(a.netEarnings().toDecimal(), a.grossEarnings().toDecimal(), a.earningsChangePercent(),
                a.occupancyRatePercent(), a.activeSpotsCount(), a.totalSpotsCount(), a.averageHourlyRate().toDecimal(),
                a.netEarnings().currency(), days);
    }
}
