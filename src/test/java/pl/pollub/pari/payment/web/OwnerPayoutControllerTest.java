package pl.pollub.pari.payment.web;

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
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.web.ApiExceptionHandler;
import pl.pollub.pari.payment.domain.Withdrawal;
import pl.pollub.pari.payment.service.OwnerPayoutService;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of the owner balance and withdrawal. The service is mocked. */
@WebMvcTest(OwnerPayoutController.class)
@Import({ApiExceptionHandler.class, SecurityConfig.class, OwnerPayoutControllerTest.Time.class})
class OwnerPayoutControllerTest {

    private static final UUID USER = UUID.randomUUID();

    @Autowired
    MockMvc mvc;
    @MockitoBean
    OwnerPayoutService payouts;

    @Test
    void balanceUsesDecimalAmountsAndTheCurrency() throws Exception {
        when(payouts.balance(USER)).thenReturn(new OwnerPayoutService.Balance(
                Money.pln(45000), Money.pln(7500), Money.pln(125000), Money.pln(9500)));

        mvc.perform(get("/api/owner/payouts/balance").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(450.0))
                .andExpect(jsonPath("$.pendingEscrow").value(75.0))
                .andExpect(jsonPath("$.totalEarned").value(1250.0))
                .andExpect(jsonPath("$.platformFeesPaid").value(95.0))
                .andExpect(jsonPath("$.currency").value("PLN"));
    }

    @Test
    void historyReturnsTheWithdrawalsWithAMaskedIbanAndTheBalance() throws Exception {
        Withdrawal w = org.mockito.Mockito.mock(Withdrawal.class);
        when(w.getId()).thenReturn(UUID.randomUUID());
        when(w.getAmount()).thenReturn(Money.pln(25000));
        when(w.getStatus()).thenReturn(pl.pollub.pari.payment.domain.WithdrawalStatus.COMPLETED);
        when(w.maskedIban()).thenReturn("****2874");
        when(w.getCreatedAt()).thenReturn(java.time.Instant.parse("2026-10-26T18:00:00Z"));
        when(payouts.history(USER)).thenReturn(java.util.List.of(w));
        when(payouts.balance(USER)).thenReturn(new OwnerPayoutService.Balance(Money.pln(45000), Money.ZERO, Money.pln(70000), Money.ZERO));

        mvc.perform(get("/api/owner/payouts/history").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].amount").value(250.0))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].iban").value("****2874"))
                .andExpect(jsonPath("$[0].availableBalance").value(450.0));
        mvc.perform(get("/api/owner/payouts/withdrawals").header("X-User-Id", USER)).andExpect(status().isOk());
    }

    @Test
    void historyWithoutWithdrawalsIsAnEmptyArrayAndNeedsLogin() throws Exception {
        when(payouts.history(USER)).thenReturn(java.util.List.of());
        when(payouts.balance(USER)).thenReturn(new OwnerPayoutService.Balance(Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO));

        mvc.perform(get("/api/owner/payouts/history").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/owner/payouts/history")).andExpect(status().isUnauthorized());
    }

    @Test
    void aNonOwnerGetsForbiddenOnTheHistory() throws Exception {
        when(payouts.history(USER)).thenThrow(new pl.pollub.pari.common.exception.ForbiddenException("Only an owner has a withdrawal history"));

        mvc.perform(get("/api/owner/payouts/history").header("X-User-Id", USER)).andExpect(status().isForbidden());
    }

    @Test
    void anInsufficientBalanceReturns409() throws Exception {
        when(payouts.withdraw(any(), any(), any())).thenThrow(new ConflictException("INSUFFICIENT_BALANCE", "The amount exceeds the available balance"));

        mvc.perform(post("/api/owner/payouts/withdraw").header("X-User-Id", USER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":400.00,\"iban\":\"PL61109010140000071219812874\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    void aMissingIbanReturns400() throws Exception {
        mvc.perform(post("/api/owner/payouts/withdraw").header("X-User-Id", USER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":400.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void withoutIdentityTheBalanceReturns401() throws Exception {
        mvc.perform(get("/api/owner/payouts/balance")).andExpect(status().isUnauthorized());
    }

    @TestConfiguration
    static class Time {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
