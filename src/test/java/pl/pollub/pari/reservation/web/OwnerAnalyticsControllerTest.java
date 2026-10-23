package pl.pollub.pari.reservation.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pl.pollub.pari.auth.security.SecurityConfig;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.web.ApiExceptionHandler;
import pl.pollub.pari.reservation.service.OwnerAnalyticsService;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OwnerAnalyticsController.class)
@Import({ApiExceptionHandler.class, SecurityConfig.class, OwnerAnalyticsControllerTest.Time.class})
class OwnerAnalyticsControllerTest {

    private static final UUID USER = UUID.randomUUID();

    @Autowired
    MockMvc mvc;
    @MockitoBean
    OwnerAnalyticsService analytics;

    @Test
    void theResponseCarriesTotalsRatesAndDaysWithPolishLabels() throws Exception {
        when(analytics.analytics(eq(USER), eq(OwnerAnalyticsService.Period.LAST_7_DAYS))).thenReturn(
                new OwnerAnalyticsService.Analytics(Money.pln(130000), Money.pln(128450), new BigDecimal("14.2"),
                        new BigDecimal("68.5"), 3, 3, Money.pln(1200),
                        List.of(new OwnerAnalyticsService.DailyAmount(LocalDate.parse("2026-10-19"), Money.pln(26000)))));

        mvc.perform(get("/api/owner/analytics").param("period", "7d").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEarnings").value(1284.5))
                .andExpect(jsonPath("$.grossEarnings").value(1300.0))
                .andExpect(jsonPath("$.earningsChangePercent").value(14.2))
                .andExpect(jsonPath("$.occupancyRatePercent").value(68.5))
                .andExpect(jsonPath("$.activeSpotsCount").value(3))
                .andExpect(jsonPath("$.totalSpotsCount").value(3))
                .andExpect(jsonPath("$.averageHourlyRate").value(12.0))
                .andExpect(jsonPath("$.dailyBreakdown[0].day").value("Pon"))
                .andExpect(jsonPath("$.dailyBreakdown[0].date").value("2026-10-19"))
                .andExpect(jsonPath("$.dailyBreakdown[0].amount").value(260.0));
    }

    @Test
    void anInvalidPeriodReturns400() throws Exception {
        mvc.perform(get("/api/owner/analytics").param("period", "1y").header("X-User-Id", USER))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withoutIdentityTheEndpointReturns401() throws Exception {
        mvc.perform(get("/api/owner/analytics")).andExpect(status().isUnauthorized());
    }

    @TestConfiguration
    static class Time {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
