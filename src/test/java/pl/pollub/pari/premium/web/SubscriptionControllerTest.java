package pl.pollub.pari.premium.web;

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
import pl.pollub.pari.payment.service.PaymentFailedException;
import pl.pollub.pari.premium.domain.PremiumPlan;
import pl.pollub.pari.premium.domain.PremiumSubscription;
import pl.pollub.pari.premium.service.PremiumService;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SubscriptionController.class)
@Import({ApiExceptionHandler.class, SecurityConfig.class, SubscriptionControllerTest.Time.class})
class SubscriptionControllerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final Instant EXPIRES = Instant.parse("2030-02-01T00:00:00Z");

    @Autowired
    MockMvc mvc;
    @MockitoBean
    PremiumService premium;

    @Test
    void subscribingReturns201WithTheStatusShape() throws Exception {
        when(premium.subscribe(eq(USER), eq(PremiumPlan.MONTHLY), eq("pm_card_visa"))).thenReturn(subscription());

        mvc.perform(post("/api/subscriptions/premium").header("X-User-Id", USER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\":\"MONTHLY\",\"paymentMethodToken\":\"pm_card_visa\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isPremium").value(true))
                .andExpect(jsonPath("$.plan").value("MONTHLY"))
                .andExpect(jsonPath("$.expiresAt").value("2030-02-01T00:00:00Z"));
    }

    @Test
    void anUnknownPlanReturns400() throws Exception {
        mvc.perform(post("/api/subscriptions/premium").header("X-User-Id", USER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\":\"WEEKLY\",\"paymentMethodToken\":\"pm_card_visa\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aDeclinedCardReturns402() throws Exception {
        when(premium.subscribe(any(), any(), any())).thenThrow(new PaymentFailedException("The card was declined"));

        mvc.perform(post("/api/subscriptions/premium").header("X-User-Id", USER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\":\"YEARLY\",\"paymentMethodToken\":\"pm_card_chargeDeclined\"}"))
                .andExpect(status().isPaymentRequired());
    }

    @Test
    void myStatusOfAnActiveOwner() throws Exception {
        when(premium.status(USER)).thenReturn(new PremiumService.Status(Optional.of(subscription())));

        mvc.perform(get("/api/subscriptions/my-status").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isPremium").value(true))
                .andExpect(jsonPath("$.plan").value("MONTHLY"));
    }

    @Test
    void myStatusOfAnOwnerWithoutAPlanIsNotPremium() throws Exception {
        when(premium.status(USER)).thenReturn(new PremiumService.Status(Optional.empty()));

        mvc.perform(get("/api/subscriptions/my-status").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isPremium").value(false))
                .andExpect(jsonPath("$.plan").doesNotExist());
    }

    private static PremiumSubscription subscription() {
        User owner = new User("o@example.com", "hash", "Olga", "Owner", null, Set.of(Role.OWNER));
        return new PremiumSubscription(owner, PremiumPlan.MONTHLY.code(), Instant.parse("2030-01-01T00:00:00Z"), EXPIRES, "ref");
    }

    @TestConfiguration
    static class Time {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
