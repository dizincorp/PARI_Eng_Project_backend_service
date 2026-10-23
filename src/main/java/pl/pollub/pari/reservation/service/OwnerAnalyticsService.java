package pl.pollub.pari.reservation.service;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.repository.ReservationRepository;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.spot.domain.SpotStatus;
import pl.pollub.pari.spot.repository.ParkingSpotRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Owner revenue statistics (PARI-24), computed from COMPLETED reservations. A reservation belongs to the day it was
 * completed (check-out time, or the reserved end when there is none), in the business time zone.
 */
@Service
public class OwnerAnalyticsService {

    public enum Period {
        LAST_7_DAYS, LAST_30_DAYS, CURRENT_MONTH;

        /** Parses the API value {@code 7d}, {@code 30d} or {@code current_month}. */
        public static Period parse(String value) {
            return switch (value == null ? "" : value) {
                case "7d" -> LAST_7_DAYS;
                case "30d" -> LAST_30_DAYS;
                case "current_month" -> CURRENT_MONTH;
                default -> throw new IllegalArgumentException("period must be 7d, 30d or current_month");
            };
        }
    }

    public record DailyAmount(LocalDate date, Money amount) {
    }

    /**
     * {@code netEarnings} = spot cost minus commission, {@code grossEarnings} = spot cost. {@code earningsChangePercent} is
     * null when the previous period earned nothing (the change is undefined). Rates are percentages with one decimal.
     */
    public record Analytics(Money grossEarnings, Money netEarnings, BigDecimal earningsChangePercent,
                            BigDecimal occupancyRatePercent, int activeSpotsCount, int totalSpotsCount,
                            Money averageHourlyRate, List<DailyAmount> dailyBreakdown) {
    }

    private final ReservationRepository reservations;
    private final ParkingSpotRepository spots;
    private final Clock clock;
    private final ZoneId zone;

    public OwnerAnalyticsService(ReservationRepository reservations, ParkingSpotRepository spots, Clock clock, ZoneId businessZone) {
        this.reservations = reservations;
        this.spots = spots;
        this.clock = clock;
        this.zone = businessZone;
    }

    @Transactional(readOnly = true)
    public Analytics analytics(UUID ownerId, Period period) {
        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        LocalDate first = switch (period) {
            case LAST_7_DAYS -> today.minusDays(6);
            case LAST_30_DAYS -> today.minusDays(29);
            case CURRENT_MONTH -> today.withDayOfMonth(1);
        };
        LocalDate end = today.plusDays(1);
        int days = (int) (end.toEpochDay() - first.toEpochDay());
        LocalDate prevFirst = period == Period.CURRENT_MONTH ? first.minusMonths(1) : first.minusDays(days);
        LocalDate prevEnd = period == Period.CURRENT_MONTH ? min(prevFirst.plusDays(days), first) : first;

        List<Reservation> current = completed(ownerId, first, end);
        List<Reservation> previous = completed(ownerId, prevFirst, prevEnd);

        long gross = current.stream().mapToLong(r -> r.getSpotCost().amountMinor()).sum();
        long net = current.stream().mapToLong(r -> net(r).amountMinor()).sum();
        long previousNet = previous.stream().mapToLong(r -> net(r).amountMinor()).sum();

        List<DailyAmount> daily = new ArrayList<>();
        for (LocalDate day = first; day.isBefore(end); day = day.plusDays(1)) {
            LocalDate d = day;
            long amount = current.stream().filter(r -> completedOn(r).equals(d)).mapToLong(r -> net(r).amountMinor()).sum();
            daily.add(new DailyAmount(d, Money.pln(amount)));
        }

        List<ParkingSpot> owned = spots.findByOwnerIdOrderByCreatedAtDesc(ownerId);
        int active = (int) owned.stream().filter(s -> s.getStatus() == SpotStatus.ACTIVE).count();
        int total = (int) owned.stream().filter(s -> s.getStatus() != SpotStatus.ARCHIVED).count();

        long avgRate = Math.round(current.stream().mapToLong(r -> r.getHourlyRate().amountMinor()).average().orElse(0));

        return new Analytics(Money.pln(gross), Money.pln(net), changePercent(net, previousNet),
                occupancy(current, active, first, end), active, total, Money.pln(avgRate), daily);
    }

    private List<Reservation> completed(UUID ownerId, LocalDate from, LocalDate to) {
        Instant fromInstant = from.atStartOfDay(zone).toInstant();
        Instant toInstant = to.atStartOfDay(zone).toInstant();
        Specification<Reservation> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("spot").get("owner").get("id"), ownerId),
                cb.equal(root.get("status"), ReservationStatus.COMPLETED),
                cb.greaterThanOrEqualTo(completedAt(root, cb), fromInstant),
                cb.lessThan(completedAt(root, cb), toInstant));
        return reservations.findAll(spec);
    }

    private static Expression<Instant> completedAt(Root<Reservation> root, CriteriaBuilder cb) {
        return cb.coalesce(root.<Instant>get("checkOutAt"), root.get("period").<Instant>get("end"));
    }

    private LocalDate completedOn(Reservation r) {
        Instant at = r.getCheckOutAt() != null ? r.getCheckOutAt() : r.getPeriod().end();
        return at.atZone(zone).toLocalDate();
    }

    private static Money net(Reservation r) {
        return r.getSpotCost().subtract(r.getCommission());
    }

    private static BigDecimal changePercent(long current, long previous) {
        if (previous == 0) {
            return current == 0 ? BigDecimal.ZERO.setScale(1) : null;
        }
        return BigDecimal.valueOf(current - previous).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(previous), 1, RoundingMode.HALF_UP);
    }

    /**
     * Reserved hours of the completed reservations inside the window over the hours the active spaces could be booked,
     * assuming they are open around the clock (availability rules are not considered). Capped at 100.
     */
    private BigDecimal occupancy(List<Reservation> completed, int activeSpots, LocalDate first, LocalDate end) {
        Instant from = first.atStartOfDay(zone).toInstant();
        Instant to = end.atStartOfDay(zone).toInstant();
        long capacityMinutes = activeSpots * Duration.between(from, to).toMinutes();
        if (capacityMinutes == 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        long booked = completed.stream().mapToLong(r -> {
            Instant s = r.getPeriod().start().isBefore(from) ? from : r.getPeriod().start();
            Instant e = r.getPeriod().end().isAfter(to) ? to : r.getPeriod().end();
            return Math.max(0, Duration.between(s, e).toMinutes());
        }).sum();
        BigDecimal rate = BigDecimal.valueOf(booked * 100).divide(BigDecimal.valueOf(capacityMinutes), 1, RoundingMode.HALF_UP);
        return rate.min(BigDecimal.valueOf(1000, 1));
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
