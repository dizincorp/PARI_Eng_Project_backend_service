package pl.pollub.pari.review;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.web.ApiExceptionHandler;
import pl.pollub.pari.review.service.ReviewService;
import pl.pollub.pari.review.web.ReviewController;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReviewController.class)
@Import({ApiExceptionHandler.class, ReviewControllerTest.Time.class})
class ReviewControllerTest {

    private static final UUID USER = UUID.randomUUID();

    @Autowired
    MockMvc mvc;
    @MockitoBean
    ReviewService reviews;

    @Test
    void creatingAReviewReturns201() throws Exception {
        when(reviews.create(any(), any(), anyInt(), any())).thenReturn(
                new ReviewService.ReviewView(UUID.randomUUID(), "Jan K.", 5, "Easy entry", Instant.parse("2029-12-01T00:00:00Z")));

        mvc.perform(post("/api/reservations/" + UUID.randomUUID() + "/reviews").header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":5,\"comment\":\"Easy entry\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorName").value("Jan K."));
    }

    @Test
    void aRatingOutsideTheRangeReturns400() throws Exception {
        mvc.perform(post("/api/reservations/" + UUID.randomUUID() + "/reviews").header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":6}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void aDuplicateReviewReturns409() throws Exception {
        when(reviews.create(any(), any(), anyInt(), any()))
                .thenThrow(new ConflictException("REVIEW_ALREADY_EXISTS", "You already reviewed this reservation"));

        mvc.perform(post("/api/reservations/" + UUID.randomUUID() + "/reviews").header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REVIEW_ALREADY_EXISTS"));
    }

    @Test
    void listingSpotReviewsIsPublic() throws Exception {
        when(reviews.listForSpot(any(), anyInt(), anyInt())).thenReturn(new ReviewService.ReviewPage(
                List.of(new ReviewService.ReviewView(UUID.randomUUID(), "Jan K.", 5, "Nice", Instant.parse("2029-12-01T00:00:00Z"))),
                1, 1, 0, 10));

        mvc.perform(get("/api/spots/" + UUID.randomUUID() + "/reviews?page=0&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].rating").value(5));
    }

    @TestConfiguration
    static class Time {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2029-12-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
