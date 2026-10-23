package pl.pollub.pari.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pl.pollub.pari.auth.security.SecurityConfig;
import pl.pollub.pari.availability.service.AvailabilityService;
import pl.pollub.pari.reservation.service.PricingService;
import pl.pollub.pari.reservation.service.ReservationService;
import pl.pollub.pari.spot.service.SpotSearchService;
import pl.pollub.pari.spot.service.SpotService;
import pl.pollub.pari.reservation.web.ReservationController;
import pl.pollub.pari.spot.web.AvailabilityController;
import pl.pollub.pari.spot.web.SpotController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The generated OpenAPI document (what Swagger UI renders) lists the REST endpoints. Services are mocked; no database. */
@WebMvcTest({SpotController.class, AvailabilityController.class, ReservationController.class})
// the WebMvcTest slice does not include springdoc's auto-configuration
@ImportAutoConfiguration({SpringDocConfiguration.class, SpringDocConfigProperties.class, SpringDocWebMvcConfiguration.class})
@Import({OpenApiConfig.class, TimeConfig.class, SecurityConfig.class})
class OpenApiDocumentTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    SpotService spotService;
    @MockitoBean
    SpotSearchService searchService;
    @MockitoBean
    pl.pollub.pari.spot.service.SpotCalendarService calendar;
    @MockitoBean
    AvailabilityService availability;
    @MockitoBean
    ReservationService reservations;
    @MockitoBean
    PricingService pricing;

    @Test
    void apiDocsListEndpointsOfEveryController() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("PARI backend API"))
                .andExpect(jsonPath("$.paths['/api/spots']").exists())
                .andExpect(jsonPath("$.paths['/api/spots/{spotId}/availability-rules']").exists())
                .andExpect(jsonPath("$.paths['/api/reservations']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                // the caller comes from the token, so it must not show up as a request parameter
                .andExpect(jsonPath("$.paths['/api/reservations'].get.parameters[?(@.name == 'userId')]").isEmpty());
    }
}
