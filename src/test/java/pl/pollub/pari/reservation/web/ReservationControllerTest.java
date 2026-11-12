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
import pl.pollub.pari.reservation.service.CheckOutView;
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
    void aMonthlyQuoteNeedsNoEndAndShowsTheDiscount() throws Exception {
        org.mockito.Mockito.when(reservations.quote(any(), any(), any(), any(), any())).thenReturn(new pl.pollub.pari.reservation.service.PricingService.Quote(
                pl.pollub.pari.common.domain.Money.pln(1000), 129_600, pl.pollub.pari.common.domain.Money.pln(1_231_200),
                pl.pollub.pari.common.domain.Money.pln(61_560), pl.pollub.pari.common.domain.Money.pln(123_120),
                pl.pollub.pari.common.domain.Money.pln(1_292_760), pl.pollub.pari.common.domain.Money.pln(1_296_000),
                new BigDecimal("5"), pl.pollub.pari.common.domain.Money.pln(64_800),
                pl.pollub.pari.reservation.domain.BookingType.MONTHLY, null, 3));

        mvc.perform(post("/api/reservations/quote").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spotId\":\"" + SPOT + "\",\"start\":\"2030-01-07T10:00:00Z\",\"bookingType\":\"MONTHLY\",\"durationMonths\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingType").value("MONTHLY"))
                .andExpect(jsonPath("$.baseCost").value(12960.00))
                .andExpect(jsonPath("$.discountPercent").value(5))
                .andExpect(jsonPath("$.discountAmount").value(648.00))
                .andExpect(jsonPath("$.durationMonths").value(3))
                .andExpect(jsonPath("$.spotCost").value(12312.00));
    }

    @Test
    void anInvalidBookingTypeReturns400() throws Exception {
        mvc.perform(post("/api/reservations/quote").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spotId\":\"" + SPOT + "\",\"start\":\"2030-01-07T10:00:00Z\",\"bookingType\":\"YEARLY\"}"))
                .andExpect(status().isBadRequest());
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
        when(reservations.checkOut(any(), any())).thenReturn(new CheckOutView(UUID.randomUUID(), "RSV-20291201-ABCDE",
                ReservationStatus.COMPLETED, Instant.parse("2026-10-22T16:30:00Z"), Instant.parse("2026-10-22T14:00:00Z"), 150,
                new BigDecimal("30.00"), new BigDecimal("45.00"), new BigDecimal("75.00"), "PLN"));
        String id = UUID.randomUUID().toString();

        mvc.perform(post("/api/reservations/" + id + "/check-in").header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pinCode\":\"4921\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mvc.perform(post("/api/reservations/" + id + "/check-out").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.actualCheckoutAt").value("2026-10-22T16:30:00Z"))
                .andExpect(jsonPath("$.plannedEndsAt").value("2026-10-22T14:00:00Z"))
                .andExpect(jsonPath("$.overstayMinutes").value(150))
                .andExpect(jsonPath("$.baseCost").value(30.00))
                .andExpect(jsonPath("$.overstayPenaltyFee").value(45.00))
                .andExpect(jsonPath("$.totalFinalAmount").value(75.00));
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
        return new ReservationView(UUID.randomUUID(), "RSV-20291201-ABCDE", SPOT, "Garage",
                "Olga", "Owner", "+48 600 100 200", null, USER, "Dan", "Driver", "+48 600 100 300", null, VEHICLE, "LU12345",
                Instant.parse("2030-01-07T10:00:00Z"), Instant.parse("2030-01-07T12:00:00Z"), status,
                new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("2.50"), new BigDecimal("22.50"), "PLN",
                pl.pollub.pari.reservation.domain.BookingType.HOURLY, null, null, new BigDecimal("20.00"), new BigDecimal("0.00"),
                status == ReservationStatus.CONFIRMED ? "4921" : null, null, null, null, null, Instant.parse("2029-12-01T00:00:00Z"), null, null, null, null,
                Instant.parse("2030-01-07T13:00:00Z"), false, false);
    }

    @TestConfiguration
    static class Time {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2029-12-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
