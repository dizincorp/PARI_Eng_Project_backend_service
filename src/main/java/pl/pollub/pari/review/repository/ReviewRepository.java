package pl.pollub.pari.review.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pl.pollub.pari.review.domain.Review;
import pl.pollub.pari.review.domain.ReviewStatus;

import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    boolean existsByReservationIdAndAuthorId(UUID reservationId, UUID authorId);

    Page<Review> findBySpotIdAndStatusOrderByCreatedAtDesc(UUID spotId, ReviewStatus status, Pageable pageable);

    Page<Review> findByRevieweeIdAndStatusOrderByCreatedAtDesc(UUID revieweeId, ReviewStatus status, Pageable pageable);

    Page<Review> findByStatusOrderByCreatedAtDesc(ReviewStatus status, Pageable pageable);

    /** Average of the visible ratings of a spot, or null without reviews. Feeds ParkingSpot.avgRating. */
    @Query("select avg(r.rating) from Review r where r.spot.id = :spotId and r.status = pl.pollub.pari.review.domain.ReviewStatus.VISIBLE")
    Double averageRatingForSpot(@Param("spotId") UUID spotId);

    @Query("select count(r) from Review r where r.spot.id = :spotId and r.status = pl.pollub.pari.review.domain.ReviewStatus.VISIBLE")
    long countVisibleForSpot(@Param("spotId") UUID spotId);

    /** Average of the visible ratings a user received. */
    @Query("select avg(r.rating) from Review r where r.reviewee.id = :userId and r.status = pl.pollub.pari.review.domain.ReviewStatus.VISIBLE")
    Double averageRatingForUser(@Param("userId") UUID userId);
}
