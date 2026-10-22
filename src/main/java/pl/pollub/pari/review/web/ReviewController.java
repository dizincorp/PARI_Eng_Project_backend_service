package pl.pollub.pari.review.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.pollub.pari.review.service.ReviewService;
import pl.pollub.pari.spot.web.SpotController;

import java.util.UUID;

/** Reviews of parking spaces. Identity is temporary, see {@link SpotController}. */
@RestController
public class ReviewController {

    public record CreateReviewRequest(@Min(1) @Max(5) int rating, @Size(max = 2000) String comment) {
    }

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    /** Reviews the space of a completed reservation. 409 when it is not completed or was already reviewed. */
    @PostMapping("/api/reservations/{id}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewService.ReviewView create(@RequestHeader(SpotController.USER_HEADER) UUID userId, @PathVariable UUID id,
                                           @Valid @RequestBody CreateReviewRequest r) {
        return reviews.create(userId, id, r.rating(), r.comment());
    }

    /** Public: visible reviews of a space, newest first. */
    @GetMapping("/api/spots/{spotId}/reviews")
    public ReviewService.ReviewPage list(@PathVariable UUID spotId,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "10") int size) {
        return reviews.listForSpot(spotId, Math.max(page, 0), Math.min(Math.max(size, 1), 50));
    }
}
