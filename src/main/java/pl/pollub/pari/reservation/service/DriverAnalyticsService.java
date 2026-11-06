package pl.pollub.pari.reservation.service;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.exception.ForbiddenException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.payment.domain.Payment;
import pl.pollub.pari.payment.domain.PaymentStatus;
import pl.pollub.pari.payment.domain.PaymentType;
import pl.pollub.pari.payment.repository.PaymentRepository;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.repository.ReservationRepository;
import pl.pollub.pari.review.domain.Review;
import pl.pollub.pari.review.repository.ReviewRepository;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.repository.UserRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Aggregated figures for the driver dashboard (PARI-48), computed on the server instead of in the browser. */
@Service
public class DriverAnalyticsService {

    /** A booking that was paid and has not been cancelled, rejected or expired. */
    private static final Set<ReservationStatus> BOOKINGS = EnumSet.of(
            ReservationStatus.PENDING_APPROVAL, ReservationStatus.CONFIRMED, ReservationStatus.IN_PROGRESS, ReservationStatus.COMPLETED);

    /** A booking that still has to be used or is being used. */
    private static final Set<ReservationStatus> ACTIVE = EnumSet.of(
            ReservationStatus.PENDING_APPROVAL, ReservationStatus.CONFIRMED, ReservationStatus.IN_PROGRESS);

    /**
     * {@code totalSpent} is what the driver really paid: successful charges and penalties minus successful refunds.
     * {@code totalSaved} is not defined by the requirements yet (the ticket gives no rule) and is always zero until
     * the team decides; it must not be filled with an invented percentage.
     */
    public record DriverAnalytics(Money totalSpent, Money totalSaved, int totalBookingsCount, int activeBookingsCount,
                                  int loyaltyPoints) {
    }

    /**
     * Figures over all the driver's COMPLETED reservations (PARI-60). {@code totalSpent} is the net amount paid for them
     * (charges and penalties minus refunds). {@code averageRating} is the mean of the driver's own ratings, or
     * {@code null} when no completed reservation was reviewed (never NaN); the other figures are 0 without sessions.
     */
    public record Summary(int completedReservations, Money totalSpent, long totalParkingDurationMinutes,
                          BigDecimal averageRating, int reviewedReservations) {
    }

    /** One completed parking session. {@code rating} is {@code null} until the driver reviews it. */
    public record SessionRow(UUID reservationId, String spotName, Instant checkInAt, Instant checkOutAt,
                             long durationMinutes, Money amountPaid, boolean reviewed, Integer rating) {
    }

    /** Statistics and one page of the sessions, newest first. */
    public record Statistics(Summary summary, List<SessionRow> items, int page, int size, long totalItems) {
    }

    private final ReservationRepository reservations;
    private final PaymentRepository payments;
    private final UserRepository users;
    private final ReviewRepository reviews;

    public DriverAnalyticsService(ReservationRepository reservations, PaymentRepository payments, UserRepository users,
                                  ReviewRepository reviews) {
        this.reservations = reservations;
        this.payments = payments;
        this.users = users;
        this.reviews = reviews;
    }

    /** Summary and a page of completed sessions of the given driver; everything comes from that driver's own records. */
    @Transactional(readOnly = true)
    public Statistics statistics(UUID driverId, int page, int size) {
        User driver = users.findById(driverId).orElseThrow(() -> new NotFoundException("User not found"));
        if (!driver.hasRole(Role.DRIVER)) {
            throw new ForbiddenException("The DRIVER role is required");
        }
        List<Reservation> completed = reservations.findByDriverIdAndStatusIn(driverId, Set.of(ReservationStatus.COMPLETED),
                Pageable.unpaged(Sort.by(Sort.Direction.DESC, "checkOutAt").and(Sort.by(Sort.Direction.DESC, "id")))).getContent();
        List<UUID> ids = completed.stream().map(Reservation::getId).toList();

        Map<UUID, Long> paid = new HashMap<>();
        Map<UUID, Review> reviewed = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Payment p : payments.findByReservationIdIn(ids)) {
                if (p.getStatus() == PaymentStatus.SUCCEEDED) {
                    long amount = p.getType() == PaymentType.REFUND ? -p.getAmount().amountMinor() : p.getAmount().amountMinor();
                    paid.merge(p.getReservation().getId(), amount, Long::sum);
                }
            }
            for (Review r : reviews.findByAuthorIdAndReservationIdIn(driverId, ids)) {
                reviewed.put(r.getReservation().getId(), r);
            }
        }

        long spent = 0;
        long minutes = 0;
        for (Reservation r : completed) {
            spent += Math.max(paid.getOrDefault(r.getId(), 0L), 0);
            minutes += durationMinutes(r);
        }
        BigDecimal average = reviewed.isEmpty() ? null
                : BigDecimal.valueOf(reviewed.values().stream().mapToInt(Review::getRating).average().orElse(0))
                        .setScale(2, RoundingMode.HALF_UP);
        Summary summary = new Summary(completed.size(), Money.pln(spent), minutes, average, reviewed.size());

        int from = (int) Math.min((long) page * size, completed.size());
        int to = (int) Math.min((long) from + size, completed.size());
        List<SessionRow> items = completed.subList(from, to).stream().map(r -> {
            Review review = reviewed.get(r.getId());
            return new SessionRow(r.getId(), r.getSpot().getName(), r.getCheckInAt(), r.getCheckOutAt(), durationMinutes(r),
                    Money.pln(Math.max(paid.getOrDefault(r.getId(), 0L), 0)), review != null,
                    review == null ? null : review.getRating());
        }).toList();
        return new Statistics(summary, items, page, size, completed.size());
    }

    private static long durationMinutes(Reservation r) {
        return r.getCheckInAt() == null || r.getCheckOutAt() == null ? 0
                : Duration.between(r.getCheckInAt(), r.getCheckOutAt()).toMinutes();
    }

    @Transactional(readOnly = true)
    public DriverAnalytics analytics(UUID driverId) {
        User driver = users.findById(driverId).orElseThrow(() -> new NotFoundException("User not found"));
        if (!driver.hasRole(Role.DRIVER)) {
            throw new ForbiddenException("The DRIVER role is required");
        }
        long spent = 0;
        for (Payment p : payments.findByDriverId(driverId, Pageable.unpaged())) {
            if (p.getStatus() != PaymentStatus.SUCCEEDED) {
                continue;
            }
            spent += p.getType() == PaymentType.REFUND ? -p.getAmount().amountMinor() : p.getAmount().amountMinor();
        }
        return new DriverAnalytics(Money.pln(Math.max(spent, 0)), Money.ZERO,
                (int) reservations.countByDriverIdAndStatusIn(driverId, BOOKINGS),
                (int) reservations.countByDriverIdAndStatusIn(driverId, ACTIVE),
                driver.getLoyaltyPoints());
    }
}
