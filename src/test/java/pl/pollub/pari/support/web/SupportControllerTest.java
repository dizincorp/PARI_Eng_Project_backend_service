package pl.pollub.pari.support.web;

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
import pl.pollub.pari.support.service.AuditEventView;
import pl.pollub.pari.support.service.ModerationService;
import pl.pollub.pari.support.service.StaffView;
import pl.pollub.pari.support.service.SupportCommandService;
import pl.pollub.pari.support.service.SupportTicketService;
import pl.pollub.pari.user.domain.Role;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of the support API: routes, status codes and the shape of the new read endpoints. Services are mocked. */
@WebMvcTest(SupportController.class)
@Import({ApiExceptionHandler.class, SecurityConfig.class, SupportControllerTest.Time.class})
class SupportControllerTest {

    private static final UUID SUPPORT = UUID.randomUUID();
    private static final UUID TICKET = UUID.randomUUID();

    @Autowired
    MockMvc mvc;
    @MockitoBean
    SupportTicketService tickets;
    @MockitoBean
    SupportCommandService commands;
    @MockitoBean
    ModerationService moderation;

    @Test
    void theQueueKeepsItsUrlAndTheStaffListIsServedNextToIt() throws Exception {
        when(tickets.queue(any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt())).thenReturn(List.of());
        when(tickets.staff(SUPPORT)).thenReturn(List.of(new StaffView(SUPPORT, "Sam Support", List.of(Role.SUPPORT))));

        mvc.perform(get("/api/support/tickets").header("X-User-Id", SUPPORT)).andExpect(status().isOk());
        mvc.perform(get("/api/support/tickets/staff").header("X-User-Id", SUPPORT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName").value("Sam Support"))
                .andExpect(jsonPath("$[0].roles[0]").value("SUPPORT"));
    }

    @Test
    void theAuditTimelineIsPaginatedAndShowsActorReasonAndOutcome() throws Exception {
        var event = new AuditEventView(7, TICKET, "SUPPORT_RELEASE", SUPPORT, "Sam Support", Instant.parse("2029-12-01T00:00:00Z"),
                "Going off shift", "SUCCEEDED", java.util.Map.of("previousAssigneeId", SUPPORT.toString()));
        when(tickets.auditTrail(SUPPORT, TICKET, 0, 50)).thenReturn(new AuditEventView.Page(List.of(event), 0, 50, 1));

        mvc.perform(get("/api/support/tickets/{id}/audit", TICKET).header("X-User-Id", SUPPORT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].action").value("SUPPORT_RELEASE"))
                .andExpect(jsonPath("$.items[0].reason").value("Going off shift"))
                .andExpect(jsonPath("$.items[0].outcome").value("SUCCEEDED"))
                .andExpect(jsonPath("$.items[0].metadata.previousAssigneeId").value(SUPPORT.toString()));
    }

    @Test
    void aNonSupportUserGets403OnTheSupportReads() throws Exception {
        when(tickets.staff(any())).thenThrow(new ForbiddenException("The SUPPORT role is required"));

        mvc.perform(get("/api/support/tickets/staff").header("X-User-Id", UUID.randomUUID()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void unblockNeedsAReasonAndAnActiveAccountAnswers409() throws Exception {
        UUID user = UUID.randomUUID();
        when(moderation.unblock(any(), any(), any())).thenThrow(new ConflictException("NOT_BLOCKED", "The account is not blocked"));

        mvc.perform(post("/api/support/users/{id}/unblock", user).header("X-User-Id", SUPPORT)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/support/users/{id}/unblock", user).header("X-User-Id", SUPPORT)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Resolved\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("NOT_BLOCKED"));
    }

    @Test
    void aLostRaceOnTheSameTicketAnswers409ConcurrentUpdate() throws Exception {
        when(tickets.claim(any(), any())).thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(
                pl.pollub.pari.support.domain.Report.class, TICKET));

        mvc.perform(post("/api/support/tickets/{id}/claim", TICKET).header("X-User-Id", SUPPORT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONCURRENT_UPDATE"));
    }

    @Test
    void assessingAPenaltyNeedsAPositiveAmountAReasonAndAnOperationId() throws Exception {
        String url = "/api/support/tickets/" + TICKET + "/penalties";
        for (String body : List.of(
                "{\"amountMinor\":0,\"reason\":\"r\",\"operationId\":\"op\"}",
                "{\"amountMinor\":-5,\"reason\":\"r\",\"operationId\":\"op\"}",
                "{\"amountMinor\":100,\"reason\":\" \",\"operationId\":\"op\"}",
                "{\"amountMinor\":100,\"reason\":\"r\"}")) {
            mvc.perform(post(url).header("X-User-Id", SUPPORT).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(commands);
    }

    @Test
    void thePenaltyAndFeeCommandsPassTheCallerAndTheOperationIdAndAnswer409WhenTheStateForbidsThem() throws Exception {
        UUID penalty = UUID.randomUUID();
        mvc.perform(post("/api/support/tickets/{id}/penalties", TICKET).header("X-User-Id", SUPPORT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":2500,\"reason\":\"Blocked the gate\",\"operationId\":\"op-1\"}"))
                .andExpect(status().isOk());
        verify(commands).assessPenalty(SUPPORT, TICKET, 2500, null, "Blocked the gate", "op-1");

        mvc.perform(post("/api/support/tickets/{id}/evacuation/fees", TICKET).header("X-User-Id", SUPPORT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":15000,\"reason\":\"Tow\",\"operationId\":\"fee-1\"}"))
                .andExpect(status().isOk());
        verify(commands).addEvacuationFee(SUPPORT, TICKET, 15000, "Tow", "fee-1");

        when(commands.waiveAssessedPenalty(any(), any(), any(), any()))
                .thenThrow(new pl.pollub.pari.common.domain.IllegalStateTransitionException("Penalty", "CHARGED", "be waived"));
        mvc.perform(post("/api/support/tickets/{id}/penalties/{p}/waive", TICKET, penalty).header("X-User-Id", SUPPORT)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Too late\"}"))
                .andExpect(status().isConflict());

        when(commands.refundEvacuationFee(any(), any(), any(), any()))
                .thenThrow(new ConflictException("EVACUATION_FEE_ALREADY_REFUNDED", "Already refunded"));
        mvc.perform(post("/api/support/tickets/{id}/evacuation/fees/{f}/refund", TICKET, penalty).header("X-User-Id", SUPPORT)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("EVACUATION_FEE_ALREADY_REFUNDED"));
    }

    @Test
    void refundingAnOverstayPenaltyNeedsAReasonAndAnOperationId() throws Exception {
        String url = "/api/support/tickets/" + TICKET + "/commands/refund-overstay-penalty";
        for (String body : List.of("{\"reason\":\"r\"}", "{\"operationId\":\"op\"}", "{\"reason\":\" \",\"operationId\":\"op\"}",
                "{\"reason\":\"r\",\"operationId\":\"\"}", "{\"reason\":\"r\",\"operationId\":\"" + "x".repeat(101) + "\"}")) {
            mvc.perform(post(url).header("X-User-Id", SUPPORT).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(commands);
    }

    @Test
    void refundingAnOverstayPenaltyPassesTheCallerAndAnswersWithTheStableErrorCodes() throws Exception {
        String url = "/api/support/tickets/" + TICKET + "/commands/refund-overstay-penalty";
        String body = "{\"reason\":\"Driver was blocked in\",\"operationId\":\"op-1\"}";

        mvc.perform(post(url).header("X-User-Id", SUPPORT).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(commands).refundOverstayPenalty(SUPPORT, TICKET, "Driver was blocked in", "op-1");

        org.mockito.Mockito.doThrow(new ConflictException(
                "OVERSTAY_PENALTY_NOT_CHARGED", "Only a charged overstay penalty can be refunded", java.util.Map.of("state", "PENDING")))
                .when(commands).refundOverstayPenalty(any(), any(), any(), any());
        mvc.perform(post(url).header("X-User-Id", SUPPORT).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OVERSTAY_PENALTY_NOT_CHARGED"))
                .andExpect(jsonPath("$.details.state").value("PENDING"));

        org.mockito.Mockito.doThrow(new pl.pollub.pari.payment.service.PaymentFailedException("Refund failed"))
                .when(commands).refundOverstayPenalty(any(), any(), any(), any());
        mvc.perform(post(url).header("X-User-Id", SUPPORT).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_FAILED"));

        org.mockito.Mockito.doThrow(new ForbiddenException("The SUPPORT role is required"))
                .when(commands).refundOverstayPenalty(any(), any(), any(), any());
        mvc.perform(post(url).header("X-User-Id", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void theSupportEndpointsNeedAuthentication() throws Exception {
        mvc.perform(get("/api/support/tickets/staff")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/support/users/blocked")).andExpect(status().isUnauthorized());
    }

    @TestConfiguration
    static class Time {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2029-12-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
