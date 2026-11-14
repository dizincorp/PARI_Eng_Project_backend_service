package pl.pollub.pari.spot.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pl.pollub.pari.auth.security.SecurityConfig;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.ForbiddenException;
import pl.pollub.pari.common.web.ApiExceptionHandler;
import pl.pollub.pari.spot.service.SpotCalendarService;
import pl.pollub.pari.spot.service.SpotSearchService;
import pl.pollub.pari.spot.service.SpotService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({SpotController.class, AvailabilityController.class})
@Import({SecurityConfig.class, ApiExceptionHandler.class, SpotControllerTest.Time.class})
class SpotControllerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final String VALID = """
            {"name":"Garage","hourlyPrice":14.50,"lengthCm":520,"widthCm":260,"heightCm":210,"amenities":["CCTV"]}""";

    @Autowired
    MockMvc mvc;
    @MockitoBean
    SpotService spots;
    @MockitoBean
    SpotSearchService search;
    @MockitoBean
    SpotCalendarService calendar;
    @MockitoBean
    pl.pollub.pari.availability.service.AvailabilityService availability;

    @Test
    void updateValidatesPriceAndDimensions() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(put("/api/spots/" + id).header("X-User-Id", USER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"G\",\"hourlyPrice\":0,\"lengthCm\":500,\"widthCm\":250}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        mvc.perform(put("/api/spots/" + id).header("X-User-Id", USER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"G\",\"hourlyPrice\":10,\"lengthCm\":-5,\"widthCm\":250}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aDailyOrMonthlyPriceMustBePositiveWhenGiven() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(put("/api/spots/" + id).header("X-User-Id", USER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"G\",\"hourlyPrice\":10,\"dailyPrice\":0,\"lengthCm\":500,\"widthCm\":250}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        mvc.perform(put("/api/spots/" + id).header("X-User-Id", USER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"G\",\"hourlyPrice\":10,\"monthlyPrice\":-1,\"lengthCm\":500,\"widthCm\":250}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateByAnotherUserReturns403() throws Exception {
        when(spots.update(any(), any(), any())).thenThrow(new ForbiddenException("Only the owner of the parking spot can do this"));

        mvc.perform(put("/api/spots/" + UUID.randomUUID()).header("X-User-Id", USER).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void deleteReturns204AndConflictWhenReservationsExist() throws Exception {
        UUID free = UUID.randomUUID();
        UUID busy = UUID.randomUUID();
        doThrow(new ConflictException("SPOT_HAS_ACTIVE_RESERVATIONS", "Cannot delete spot with active or upcoming reservations."))
                .when(spots).archive(USER, busy);

        mvc.perform(delete("/api/spots/" + free).header("X-User-Id", USER)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/spots/" + busy).header("X-User-Id", USER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Cannot delete spot with active or upcoming reservations."));
    }

    @Test
    void bookedSlotsNeedLoginAndAWindow() throws Exception {
        UUID id = UUID.randomUUID();
        when(calendar.bookedSlots(any(), any(), any(), any(), any())).thenReturn(List.of(
                new SpotCalendarService.BookedSlot(null, null, Instant.parse("2030-01-07T10:00:00Z"), Instant.parse("2030-01-07T12:00:00Z"), "CONFIRMED", UUID.randomUUID(), "#1")));

        mvc.perform(get("/api/spots/" + id + "/booked-slots?from=2030-01-07T00:00:00Z&to=2030-01-08T00:00:00Z"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/spots/" + id + "/booked-slots?from=2030-01-07T00:00:00Z&to=2030-01-08T00:00:00Z").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("CONFIRMED"));
        mvc.perform(get("/api/spots/" + id + "/booked-slots").header("X-User-Id", USER)).andExpect(status().isBadRequest());
    }

    @Test
    void calendarBlocksAreAnAliasOfBlockedPeriods() throws Exception {
        UUID id = UUID.randomUUID();
        when(availability.blockPeriod(any(), any(), any(), any(), any())).thenThrow(
                new ConflictException("PERIOD_HAS_RESERVATIONS", "A reservation already exists in this period"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/spots/" + id + "/calendar/blocks")
                        .header("X-User-Id", USER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startsAt\":\"2026-10-25T10:00:00Z\",\"endsAt\":\"2026-10-25T18:00:00Z\",\"reason\":\"Family visit\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PERIOD_HAS_RESERVATIONS"));
        mvc.perform(delete("/api/spots/" + id + "/calendar/blocks/" + UUID.randomUUID()).header("X-User-Id", USER))
                .andExpect(status().isNoContent());
    }

    @TestConfiguration
    static class Time {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2029-12-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
