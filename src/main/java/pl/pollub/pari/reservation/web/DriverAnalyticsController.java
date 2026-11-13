package pl.pollub.pari.reservation.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.pollub.pari.auth.security.CurrentUserId;
import pl.pollub.pari.reservation.service.DriverAnalyticsService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Statistics for the driver dashboard (PARI-48). */
@RestController
@RequestMapping("/api/driver/analytics")
public class DriverAnalyticsController {

    /**
     * The first six fields are the PARI-48 figures. {@code totalSaved} is 0.00 until the requirements define what counts
     * as a saving. {@code summary} and {@code items} (PARI-60) describe the completed sessions: {@code items} is one page,
     * newest first; {@code averageRating} is null without any review.
     */
    public record DriverAnalyticsResponse(BigDecimal totalSpent, BigDecimal totalSaved, int totalBookingsCount,
                                          int activeBookingsCount, int loyaltyPoints, String currency,
                                          Summary summary, List<Item> items, int page, int size, long totalItems) {
    }

    public record Summary(int completedReservations, BigDecimal totalSpent, long totalParkingDurationMinutes,
                          BigDecimal averageRating, int reviewedReservations) {
    }

    public record Item(UUID reservationId, String spotName, Instant checkInAt, Instant checkOutAt, Long durationMinutes,
                       BigDecimal amountPaid, String currency, boolean reviewed, Integer rating) {
    }

    private final DriverAnalyticsService analytics;

    public DriverAnalyticsController(DriverAnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping
    public DriverAnalyticsResponse analytics(@CurrentUserId UUID userId,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        DriverAnalyticsService.DriverAnalytics a = analytics.analytics(userId);
        int pageNo = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), 100);
        DriverAnalyticsService.Statistics s = analytics.statistics(userId, pageNo, pageSize);
        DriverAnalyticsService.Summary sum = s.summary();
        List<Item> items = s.items().stream().map(r -> new Item(r.reservationId(), r.spotName(), r.checkInAt(), r.checkOutAt(),
                r.durationMinutes(), r.amountPaid().toDecimal(), r.amountPaid().currency(), r.reviewed(), r.rating())).toList();
        return new DriverAnalyticsResponse(a.totalSpent().toDecimal(), a.totalSaved().toDecimal(), a.totalBookingsCount(),
                a.activeBookingsCount(), a.loyaltyPoints(), a.totalSpent().currency(),
                new Summary(sum.completedReservations(), sum.totalSpent().toDecimal(), sum.totalParkingDurationMinutes(),
                        sum.averageRating(), sum.reviewedReservations()),
                items, pageNo, pageSize, s.totalItems());
    }
}
