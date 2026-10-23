package pl.pollub.pari.user.web;

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
import pl.pollub.pari.common.web.ApiExceptionHandler;
import pl.pollub.pari.user.service.VehicleService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VehicleController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class, VehicleControllerTest.Time.class})
class VehicleControllerTest {

    private static final UUID USER = UUID.randomUUID();

    @Autowired
    MockMvc mvc;
    @MockitoBean
    VehicleService vehicles;

    @Test
    void creatingAVehicleReturns201() throws Exception {
        when(vehicles.create(any(), any(), any())).thenReturn(
                new VehicleService.VehicleView(UUID.randomUUID(), "Toyota Yaris", "WA98765", Instant.parse("2029-12-01T00:00:00Z")));

        mvc.perform(post("/api/vehicles").header("X-User-Id", USER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"Toyota Yaris\",\"plateNumber\":\"WA 98765\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plateNumber").value("WA98765"));
    }

    @Test
    void anInvalidPlateReturns400() throws Exception {
        mvc.perform(post("/api/vehicles").header("X-User-Id", USER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"Yaris\",\"plateNumber\":\"!!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void deletingReturns204() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(delete("/api/vehicles/" + id).header("X-User-Id", USER)).andExpect(status().isNoContent());
        verify(vehicles).delete(USER, id);
    }

    @TestConfiguration
    static class Time {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2029-12-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
