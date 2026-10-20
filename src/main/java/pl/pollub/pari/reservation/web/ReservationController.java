package pl.pollub.pari.reservation.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.pollub.pari.payment.service.PaymentFailedException;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.service.PricingService;
import pl.pollub.pari.reservation.service.ReservationService;
import pl.pollub.pari.reservation.service.ReservationView;
import pl.pollub.pari.spot.web.SpotController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Booking and managing reservations. Identity is temporary, see {@link SpotController}. */
@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    public record QuoteRequest(@NotNull UUID spotId, @NotNull Instant start, @NotNull Instant end) {
    }

    public record QuoteResponse(long durationMinutes, BigDecimal hourlyRate, BigDecimal spotCost, BigDecimal serviceFee,
                                BigDecimal total, String currency) {

        static QuoteResponse of(PricingService.Quote q) {
            return new QuoteResponse(q.minutes(), q.hourlyRate().toDecimal(), q.spotCost().toDecimal(),
                    q.serviceFee().toDecimal(), q.total().toDecimal(), q.total().currency());
        }
    }

    /** {@code paymentMethodToken} is the provider's payment method id. With the mock gateway use pm_card_visa; pm_card_chargeDeclined is declined. */
    public record BookRequest(@NotNull UUID spotId, @NotNull UUID vehicleId, @NotNull Instant start, @NotNull Instant end,
                              @NotBlank String paymentMethodToken) {
    }

    public record ReasonRequest(String reason) {
    }

    /** Give the PIN or the QR token that was issued at booking. */
    public record CheckInRequest(String pinCode, String qrToken) {
    }

    private final ReservationService reservations;

    public ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    @PostMapping("/quote")
    public QuoteResponse quote(@Valid @RequestBody QuoteRequest r) {
        return QuoteResponse.of(reservations.quote(r.spotId(), r.start(), r.end()));
    }

    /** Answers 201 with the reservation, or 402 when the payment is declined (the reservation is then saved as CANCELLED). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationView book(@RequestHeader(SpotController.USER_HEADER) UUID userId, @Valid @RequestBody BookRequest r) {
        ReservationService.BookingResult result = reservations.book(userId,
                new ReservationService.BookCommand(r.spotId(), r.vehicleId(), r.start(), r.end(), r.paymentMethodToken()));
        if (result.paymentFailed()) {
            throw new PaymentFailedException(result.paymentFailureReason());
        }
        return result.reservation();
    }

    /** Reservations of the caller as a driver. */
    @GetMapping
    public List<ReservationView> mine(@RequestHeader(SpotController.USER_HEADER) UUID userId,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return reservations.listForDriver(userId, page, Math.min(Math.max(size, 1), 50));
    }

    /** Reservations of the caller's spaces. */
    @GetMapping("/incoming")
    public List<ReservationView> incoming(@RequestHeader(SpotController.USER_HEADER) UUID userId,
                                          @RequestParam(required = false) UUID spotId,
                                          @RequestParam(required = false) ReservationStatus status,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return reservations.listForOwner(userId, spotId, status, page, Math.min(Math.max(size, 1), 50));
    }

    /** The driver's running parking session; 404 when there is none. */
    @GetMapping("/active-session")
    public ReservationView activeSession(@RequestHeader(SpotController.USER_HEADER) UUID userId) {
        return reservations.activeSession(userId);
    }

    @GetMapping("/{id}")
    public ReservationView get(@RequestHeader(SpotController.USER_HEADER) UUID userId, @PathVariable UUID id) {
        return reservations.get(userId, id);
    }

    @PostMapping("/{id}/cancel")
    public ReservationView cancel(@RequestHeader(SpotController.USER_HEADER) UUID userId, @PathVariable UUID id,
                                  @RequestBody(required = false) ReasonRequest r) {
        return reservations.cancel(userId, id, r == null || r.reason() == null ? "Cancelled by user" : r.reason());
    }

    @PostMapping("/{id}/accept")
    public ReservationView accept(@RequestHeader(SpotController.USER_HEADER) UUID userId, @PathVariable UUID id) {
        return reservations.accept(userId, id);
    }

    @PostMapping("/{id}/reject")
    public ReservationView reject(@RequestHeader(SpotController.USER_HEADER) UUID userId, @PathVariable UUID id,
                                  @RequestBody(required = false) ReasonRequest r) {
        return reservations.reject(userId, id, r == null || r.reason() == null ? "Rejected by owner" : r.reason());
    }

    /** Starts the parking session. 403 for a wrong code, 409 outside the reserved period. */
    @PostMapping("/{id}/check-in")
    public ReservationView checkIn(@RequestHeader(SpotController.USER_HEADER) UUID userId, @PathVariable UUID id,
                                   @RequestBody CheckInRequest r) {
        return reservations.checkIn(userId, id, r.pinCode(), r.qrToken());
    }

    /** Ends the parking session; time over the reserved end is charged as a penalty. */
    @PostMapping("/{id}/check-out")
    public ReservationView checkOut(@RequestHeader(SpotController.USER_HEADER) UUID userId, @PathVariable UUID id) {
        return reservations.checkOut(userId, id);
    }
}
