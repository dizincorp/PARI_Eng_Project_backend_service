package pl.pollub.pari.review.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.repository.ReservationRepository;
import pl.pollub.pari.review.domain.Review;
import pl.pollub.pari.review.domain.ReviewStatus;
import pl.pollub.pari.review.repository.ReviewRepository;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.spot.repository.ParkingSpotRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Driver reviews of a parking space after a finished reservation (F20), and the cached rating of the space. */
@Service
public class ReviewService {

    /** What the public sees. The author is shown as first name and the initial of the last name, for example "Jan K.". */
    public record ReviewView(UUID id, String authorName, int rating, String comment, Instant createdAt) {

        static ReviewView of(Review r) {
            String last = r.getAuthor().getLastName();
            String initial = last == null || last.isBlank() ? "" : " " + Character.toUpperCase(last.trim().charAt(0)) + ".";
            return new ReviewView(r.getId(), r.getAuthor().getFirstName() + initial, r.getRating(), r.getComment(), r.getCreatedAt());
        }
    }

    public record ReviewPage(List<ReviewView> content, long totalElements, int totalPages, int page, int size) {
    }

    private final ReviewRepository reviews;
    private final ReservationRepository reservations;
    private final ParkingSpotRepository spots;

    public ReviewService(ReviewRepository reviews, ReservationRepository reservations, ParkingSpotRepository spots) {
        this.reviews = reviews;
        this.reservations = reservations;
        this.spots = spots;
    }

    /** The driver of a COMPLETED reservation rates the space. One review per reservation; the space's cached rating is updated. */
    @Transactional
    public ReviewView create(UUID driverId, UUID reservationId, int rating, String comment) {
        Reservation reservation = reservations.findById(reservationId)
                .filter(r -> r.getDriver().getId().equals(driverId))
                .orElseThrow(() -> new NotFoundException("Reservation not found"));
        if (reservation.getStatus() != ReservationStatus.COMPLETED) {
            throw new ConflictException("RESERVATION_NOT_COMPLETED", "Only a completed reservation can be reviewed");
        }
        if (reviews.existsByReservationIdAndAuthorId(reservationId, driverId)) {
            throw alreadyReviewed();
        }
        ParkingSpot spot = reservation.getSpot();
        Review review = new Review(reservation, reservation.getDriver(), spot.getOwner(), spot, rating,
                comment == null || comment.isBlank() ? null : comment.trim());
        try {
            reviews.saveAndFlush(review);
        } catch (DataIntegrityViolationException e) {
            throw alreadyReviewed();
        }
        refreshRating(spot);
        return ReviewView.of(review);
    }

    /** Visible reviews of a space, newest first. */
    @Transactional(readOnly = true)
    public ReviewPage listForSpot(UUID spotId, int page, int size) {
        if (!spots.existsById(spotId)) {
            throw new NotFoundException("Parking spot not found");
        }
        Page<Review> result = reviews.findBySpotIdAndStatusOrderByCreatedAtDesc(spotId, ReviewStatus.VISIBLE, PageRequest.of(page, size));
        return new ReviewPage(result.map(ReviewView::of).getContent(), result.getTotalElements(), result.getTotalPages(), page, size);
    }

    private void refreshRating(ParkingSpot spot) {
        Double average = reviews.averageRatingForSpot(spot.getId());
        long count = reviews.countVisibleForSpot(spot.getId());
        spot.updateRating(average == null ? BigDecimal.ZERO : BigDecimal.valueOf(average).setScale(2, RoundingMode.HALF_UP), (int) count);
    }

    private static ConflictException alreadyReviewed() {
        return new ConflictException("REVIEW_ALREADY_EXISTS", "You already reviewed this reservation");
    }
}
