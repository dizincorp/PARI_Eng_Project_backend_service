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
import pl.pollub.pari.spot.service.SpotService;
import pl.pollub.pari.spot.service.SpotUnitService;
import pl.pollub.pari.spot.service.SpotView;
import pl.pollub.pari.spot.service.SpotView.UnitRef;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SpotUnitController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class, SpotUnitControllerTest.Config.class})
class SpotUnitControllerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID SPOT = UUID.randomUUID();

    @Autowired
    MockMvc mvc;
    @MockitoBean
    SpotService spots;
    @MockitoBean
    SpotUnitService units;

    @Test
    void theSpacesOfAListingArePublic() throws Exception {
        UUID unit = UUID.randomUUID();
        SpotView view = org.mockito.Mockito.mock(SpotView.class);
        when(view.units()).thenReturn(List.of(new UnitRef(unit, "#1", 1)));
        when(spots.get(SPOT, null)).thenReturn(view);

        mvc.perform(get("/api/spots/" + SPOT + "/units"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(unit.toString()))
                .andExpect(jsonPath("$[0].label").value("#1"));
    }

    @Test
    void changingSpacesNeedsAuthentication() throws Exception {
        mvc.perform(post("/api/spots/" + SPOT + "/units")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/spots/" + SPOT + "/units/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"A\"}")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/spots/" + SPOT + "/units/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void addingWithAndWithoutALabelPassesTheCaller() throws Exception {
        when(units.add(eq(USER), eq(SPOT), any())).thenReturn(List.of(new UnitRef(UUID.randomUUID(), "#1", 1)));

        mvc.perform(post("/api/spots/" + SPOT + "/units").header("X-User-Id", USER)).andExpect(status().isCreated());
        verify(units).add(USER, SPOT, null);
        mvc.perform(post("/api/spots/" + SPOT + "/units").header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"North\"}"))
                .andExpect(status().isCreated());
        verify(units).add(USER, SPOT, "North");
    }

    @Test
    void aTooLongOrBlankLabelIsAnswered400() throws Exception {
        mvc.perform(post("/api/spots/" + SPOT + "/units").header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"" + "x".repeat(21) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/spots/" + SPOT + "/units/" + UUID.randomUUID()).header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusalsAreMappedToTheirStatusAndErrorCode() throws Exception {
        UUID unit = UUID.randomUUID();
        when(units.remove(USER, SPOT, unit)).thenThrow(new ConflictException("SPOT_UNIT_HAS_RESERVATIONS", "held"));
        mvc.perform(delete("/api/spots/" + SPOT + "/units/" + unit).header("X-User-Id", USER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SPOT_UNIT_HAS_RESERVATIONS"));

        UUID stranger = UUID.randomUUID();
        when(units.rename(eq(stranger), eq(SPOT), eq(unit), any())).thenThrow(new ForbiddenException("no"));
        mvc.perform(put("/api/spots/" + SPOT + "/units/" + unit).header("X-User-Id", stranger)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"A\"}"))
                .andExpect(status().isForbidden());
    }

    @TestConfiguration
    static class Config {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2029-12-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
