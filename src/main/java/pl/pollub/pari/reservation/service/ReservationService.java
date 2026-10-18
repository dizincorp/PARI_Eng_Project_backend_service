package pl.pollub.pari.reservation.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.availability.service.AvailabilityService;
import pl.pollub.pari.common.domain.TimeRange;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.ForbiddenException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.common.service.AuditTrail;
import pl.pollub.pari.payment.service.PaymentService;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.repository.ReservationRepository;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.spot.repository.ParkingSpotRepository;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;
import pl.pollub.pari.user.repository.UserRepository;
import pl.pollub.pari.user.repository.VehicleRepository;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Booking a parking space (F05, F13): price, availability and slot checks, payment, and the owner's accept or reject
 * decision. Check-in, check-out and overstay penalties (F14, F15, F19) are not part of this service yet.
 */
@Service
public class ReservationService {

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final DateTimeFormatter CODE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What the driver sends when booking. {@code paymentMethodToken} is the provider's payment method id (Stripe pm_...). */
    public record BookCommand(UUID spotId, UUID vehicleId, Instant start, Instant end, String paymentMethodToken) {
    }

    /**
     * Result of booking. When the payment is declined the reservation is saved as CANCELLED (the slot is freed) and
     * {@code paymentFailureReason} says why; the caller answers the driver with HTTP 402.
     */
    public record BookingResult(ReservationView reservation, String paymentFailureReason) {

        public boolean paymentFailed() {
            return paymentFailureReason != null;
        }
    }

    private final ReservationRepository reservations;
    private final ParkingSpotRepository spots;
    private final UserRepository users;
    private final VehicleRepository vehicles;
    private final AvailabilityService availability;
    private final PricingService pricing;
    private final PaymentService payments;
    private final AuditTrail audit;
    private final Clock clock;

    public ReservationService(ReservationRepository reservations, ParkingSpotRepository spots, UserRepository users,
                              VehicleRepository vehicles, AvailabilityService availability, PricingService pricing,
                              PaymentService payments, AuditTrail audit, Clock clock) {
        this.reservations = reservations;
        this.spots = spots;
        this.users = users;
        this.vehicles = vehicles;
        this.availability = availability;
        this.pricing = pricing;
        this.payments = payments;
        this.audit = audit;
        this.clock = clock;
    }

    /** Price preview shown before the driver pays. Checks that the space exists and is bookable, not that the slot is free. */
    @Transactional(readOnly = true)
    public PricingService.Quote quote(UUID spotId, Instant start, Instant end) {
        ParkingSpot spot = spots.findById(spotId).orElseThrow(() -> new NotFoundException("Parking spot not found"));
        if (!spot.isBookable()) {
            throw new ConflictException("SPOT_NOT_BOOKABLE", "The parking spot is not available for reservations");
        }
        return pricing.quote(spot, new TimeRange(start, end));
    }

    /**
     * Books a space and takes the payment. The slot is held from the moment the reservation is saved; the database
     * exclusion constraint guarantees that two concurrent bookings of the same slot cannot both succeed.
     */
    @Transactional
    public BookingResult book(UUID driverId, BookCommand cmd) {
        Instant now = clock.instant();
        User driver = users.findById(driverId).orElseThrow(() -> new NotFoundException("User not found"));
        if (driver.isBlocked()) {
            throw new ForbiddenException("The account is blocked");
        }
        if (!driver.hasRole(Role.DRIVER)) {
            throw new ForbiddenException("The DRIVER role is required to book a parking space");
        }
        ParkingSpot spot = spots.findById(cmd.spotId()).orElseThrow(() -> new NotFoundException("Parking spot not found"));
        if (!spot.isBookable() || spot.getOwner().isBlocked()) {
            throw new ConflictException("SPOT_NOT_BOOKABLE", "The parking spot is not available for reservations");
        }
        if (spot.getOwner().getId().equals(driverId)) {
            throw new ForbiddenException("You cannot book your own parking space");
        }
        Vehicle vehicle = vehicles.findByIdAndUserId(cmd.vehicleId(), driverId)
                .orElseThrow(() -> new NotFoundException("Vehicle not found"));

        TimeRange period = new TimeRange(cmd.start(), cmd.end());
        if (period.start().isBefore(now)) {
            throw new IllegalArgumentException("A reservation must start in the future");
        }
        if (!availability.isAvailable(spot, period)) {
            throw new ConflictException("SPOT_NOT_AVAILABLE", "The owner does not offer this space at the requested time");
        }
        if (reservations.existsOverlapping(spot.getId(), ReservationStatus.SLOT_HOLDING, period.start(), period.end())) {
            throw slotTaken();
        }

        PricingService.Quote quote = pricing.quote(spot, period);
        Reservation reservation = new Reservation(newCode(now), spot, driver, vehicle, period, quote.hourlyRate(),
                quote.spotCost(), quote.serviceFee(), quote.commission());
        try {
            reservations.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException e) {
            throw slotTaken();
        }

        PaymentService.ChargeOutcome charge = payments.charge(reservation, cmd.paymentMethodToken());
        if (!charge.succeeded()) {
            reservation.cancel("Payment failed: " + charge.failureReason(), now);
            audit.record(driverId, "Reservation", reservation.getId(), "PAYMENT_FAILED",
                    Map.of("code", reservation.getCode(), "reason", String.valueOf(charge.failureReason())));
            return new BookingResult(ReservationView.of(reservation, driverId), charge.failureReason());
        }

        reservation.markPaid(spot.isAutoAccept());
        payments.schedulePayout(reservation);
        audit.record(driverId, "Reservation", reservation.getId(), "BOOKED",
                Map.of("code", reservation.getCode(), "status", reservation.getStatus().name(),
                        "totalMinor", reservation.getTotal().amountMinor()));
        return new BookingResult(ReservationView.of(reservation, driverId), null);
    }

    /** The owner accepts a paid request (only when auto-accept is off). */
    @Transactional
    public ReservationView accept(UUID ownerId, UUID reservationId) {
        Reservation reservation = loadForOwner(ownerId, reservationId);
        reservation.accept();
        audit.record(ownerId, "Reservation", reservation.getId(), "ACCEPTED", Map.of("code", reservation.getCode()));
        return ReservationView.of(reservation, ownerId);
    }

    /** The owner declines a paid request; the driver gets the whole payment back. */
    @Transactional
    public ReservationView reject(UUID ownerId, UUID reservationId, String reason) {
        Reservation reservation = loadForOwner(ownerId, reservationId);
        reservation.reject(reason, clock.instant());
        payments.refundInFull(reservation);
        audit.record(ownerId, "Reservation", reservation.getId(), "REJECTED", Map.of("code", reservation.getCode()));
        return ReservationView.of(reservation, ownerId);
    }

    /**
     * The driver or the owner cancels before check-in. PLACEHOLDER rule: always a full refund, because the plan defines
     * no cancellation fees yet; replace it when the team decides.
     */
    @Transactional
    public ReservationView cancel(UUID actorId, UUID reservationId, String reason) {
        Reservation reservation = loadForParticipant(actorId, reservationId);
        reservation.cancel(reason, clock.instant());
        payments.refundInFull(reservation);
        audit.record(actorId, "Reservation", reservation.getId(), "CANCELLED", Map.of("code", reservation.getCode()));
        return ReservationView.of(reservation, actorId);
    }

    @Transactional(readOnly = true)
    public ReservationView get(UUID viewerId, UUID reservationId) {
        return ReservationView.of(loadForParticipant(viewerId, reservationId), viewerId);
    }

    /** Reservations the user made as a driver, newest first. */
    @Transactional(readOnly = true)
    public List<ReservationView> listForDriver(UUID driverId, int page, int size) {
        return reservations.findByDriverId(driverId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(r -> ReservationView.of(r, driverId)).getContent();
    }

    /** Reservations of the owner's spaces, optionally for one space or status, newest start first. */
    @Transactional(readOnly = true)
    public List<ReservationView> listForOwner(UUID ownerId, UUID spotId, ReservationStatus status, int page, int size) {
        return reservations.findForOwner(ownerId, spotId, status, null, null,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "period.start")))
                .map(r -> ReservationView.of(r, ownerId)).getContent();
    }

    private Reservation loadForOwner(UUID ownerId, UUID reservationId) {
        Reservation reservation = reservations.findById(reservationId)
                .orElseThrow(() -> new NotFoundException("Reservation not found"));
        if (!reservation.getSpot().getOwner().getId().equals(ownerId)) {
            throw new NotFoundException("Reservation not found");
        }
        return reservation;
    }

    private Reservation loadForParticipant(UUID userId, UUID reservationId) {
        Reservation reservation = reservations.findById(reservationId)
                .orElseThrow(() -> new NotFoundException("Reservation not found"));
        boolean participant = reservation.getDriver().getId().equals(userId)
                || reservation.getSpot().getOwner().getId().equals(userId);
        if (!participant) {
            throw new NotFoundException("Reservation not found");
        }
        return reservation;
    }

    private static ConflictException slotTaken() {
        return new ConflictException("SPOT_ALREADY_RESERVED", "The selected time is already reserved");
    }

    private static String newCode(Instant now) {
        StringBuilder suffix = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            suffix.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return "RSV-" + CODE_DATE.format(now) + "-" + suffix;
    }
}
