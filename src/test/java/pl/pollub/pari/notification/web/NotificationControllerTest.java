package pl.pollub.pari.notification.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pl.pollub.pari.auth.security.SecurityConfig;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.common.web.ApiExceptionHandler;
import pl.pollub.pari.notification.service.NotificationService;
import pl.pollub.pari.notification.service.NotificationView;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
@Import({ApiExceptionHandler.class, SecurityConfig.class, NotificationControllerTest.Time.class})
class NotificationControllerTest {

    private static final UUID USER = UUID.randomUUID();

    @Autowired
    MockMvc mvc;
    @MockitoBean
    NotificationService notifications;

    @Test
    void listReturnsTheNotificationsInTheDocumentedShape() throws Exception {
        UUID id = UUID.randomUUID();
        when(notifications.list(eq(USER), eq(true), eq(0), eq(20))).thenReturn(List.of(new NotificationView(
                id, "Rezerwacja potwierdzona", "PIN: 4921", "RESERVATION_CONFIRMED", false, Instant.parse("2026-10-22T14:00:00Z"))));

        mvc.perform(get("/api/notifications?unreadOnly=true").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].title").value("Rezerwacja potwierdzona"))
                .andExpect(jsonPath("$[0].message").value("PIN: 4921"))
                .andExpect(jsonPath("$[0].type").value("RESERVATION_CONFIRMED"))
                .andExpect(jsonPath("$[0].read").value(false))
                .andExpect(jsonPath("$[0].createdAt").value("2026-10-22T14:00:00Z"));
    }

    @Test
    void markReadPassesTheCallerAndReturns404ForSomebodyElsesNotification() throws Exception {
        UUID id = UUID.randomUUID();
        when(notifications.markRead(any(), eq(id))).thenThrow(new NotFoundException("Notification not found"));

        mvc.perform(patch("/api/notifications/" + id + "/read").header("X-User-Id", USER))
                .andExpect(status().isNotFound());
        verify(notifications).markRead(USER, id);
    }

    @Test
    void anonymousCallersGet401() throws Exception {
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
    }

    @TestConfiguration
    static class Time {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2029-12-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
