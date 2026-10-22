package pl.pollub.pari.reservation.web;

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
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.common.web.ApiExceptionHandler;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.service.ReservationService;
import pl.pollub.pari.reservation.service.ReservationView;

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

/** HTTP contract of booking: status codes and the error body. Services are mocked; the database is not involved. */
@WebMvcTest(ReservationController.class)
@Import({ApiExceptionHandler.class, SecurityConfig.class, ReservationControllerTest.Time.class})
class ReservationControllerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID SPOT = UUID.randomUUID();
    private static final UUID VEHICLE = UUID.randomUUID();

    @Autowired
    MockMvc mvc;
    @MockitoBean
    ReservationService reservations;

    @Test
    void bookingReturns201WithTheReservation() throws Exception {
        when(reservations.book(any(), any())).thenReturn(new ReservationService.BookingResult(view(ReservationStatus.CONFIRMED), null));

        mvc.perform(post("/api/reservations").header("X-User-Id", USER).contentType(MediaType.APPLICATION_JSON).content(body("pm_card_visa")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.pinCode").value("4921"));
    }

    @Test
    void aDeclinedPaymentReturns402WithTheReason() throws Exception {
        when(reservations.book(any(), any())).thenReturn(
                new ReservationService.BookingResult(view(ReservationStatus.CANCELLED), "The card was declined"));

        mvc.perform(post("/api/reservations").header("X-User-Id", USER).contentType(MediaType.APPLICATION_JSON).content(body("pm_card_chargeDeclined")))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.message").value("The card was declined"))
                .andExpect(jsonPath("$.statusCode").value(402));
    }

    @Test
    void aTakenSlotReturns409WithItsErrorCode() throws Exception {
        when(reservations.book(any(), any())).thenThrow(new ConflictException("SPOT_ALREADY_RESERVED", "The selected time is already reserved"));

        mvc.perform(post("/api/reservations").header("X-User-Id", USER).contentType(MediaType.APPLICATION_JSON).content(body("pm_card_visa")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SPOT_ALREADY_RESERVED"));
    }

    @Test
    void aMissingIdentityReturns401() throws Exception {
        mvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON).content(body("pm_card_visa")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
    }

    @Test
    void anInvalidBodyReturns400ListingTheFields() throws Exception {
        mvc.perform(post("/api/reservations").header("X-User-Id", USER).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("paymentMethodToken")));
    }

    @Test
    void aWrongAccessCodeAtCheckInReturns403() throws Exception {
        when(reservations.checkIn(any(), any(), any(), any())).thenThrow(new ForbiddenException("Invalid access code"));

        mvc.perform(post("/api/reservations/" + UUID.randomUUID() + "/check-in").header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pinCode\":\"0000\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void checkInAndCheckOutReturnTheReservation() throws Exception {
        when(reservations.checkIn(any(), any(), any(), any())).thenReturn(view(ReservationStatus.IN_PROGRESS));
        when(reservations.checkOut(any(), any())).thenReturn(view(ReservationStatus.COMPLETED));
        String id = UUID.randomUUID().toString();

        mvc.perform(post("/api/reservations/" + id + "/check-in").header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pinCode\":\"4921\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mvc.perform(post("/api/reservations/" + id + "/check-out").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void noActiveSessionReturns404() throws Exception {
        when(reservations.activeSession(any())).thenThrow(new NotFoundException("No active parking session"));

        mvc.perform(get("/api/reservations/active-session").header("X-User-Id", USER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No active parking session"));
    }

    private static String body(String token) {
        return """
                {"spotId":"%s","vehicleId":"%s","start":"2030-01-07T10:00:00Z","end":"2030-01-07T12:00:00Z","paymentMethodToken":"%s"}
                """.formatted(SPOT, VEHICLE, token);
    }

    private static ReservationView view(ReservationStatus status) {
        return new ReservationView(UUID.randomUUID(), "RSV-20291201-ABCDE", SPOT, "Garage", USER, VEHICLE, "LU12345",
                Instant.parse("2030-01-07T10:00:00Z"), Instant.parse("2030-01-07T12:00:00Z"), status,
                new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("1.00"), new BigDecimal("21.00"), "PLN",
                status == ReservationStatus.CONFIRMED ? "4921" : null, null, null, Instant.parse("2029-12-01T00:00:00Z"), null, null, null, null);
    }

    @TestConfiguration
    static class Time {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2029-12-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
