package pl.pollub.pari.reservation.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.pollub.pari.auth.security.CurrentUserId;
import pl.pollub.pari.reservation.service.DriverAnalyticsService;

import java.math.BigDecimal;
import java.util.UUID;

/** Statistics for the driver dashboard (PARI-48). */
@RestController
@RequestMapping("/api/driver/analytics")
public class DriverAnalyticsController {

    /** {@code totalSaved} is 0.00 until the requirements define what counts as a saving. */
    public record DriverAnalyticsResponse(BigDecimal totalSpent, BigDecimal totalSaved, int totalBookingsCount,
                                          int activeBookingsCount, int loyaltyPoints, String currency) {
    }

    private final DriverAnalyticsService analytics;

    public DriverAnalyticsController(DriverAnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping
    public DriverAnalyticsResponse analytics(@CurrentUserId UUID userId) {
        DriverAnalyticsService.DriverAnalytics a = analytics.analytics(userId);
        return new DriverAnalyticsResponse(a.totalSpent().toDecimal(), a.totalSaved().toDecimal(), a.totalBookingsCount(),
                a.activeBookingsCount(), a.loyaltyPoints(), a.totalSpent().currency());
    }
}
