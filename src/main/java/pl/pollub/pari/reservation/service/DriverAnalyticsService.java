package pl.pollub.pari.reservation.service;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.exception.ForbiddenException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.payment.domain.Payment;
import pl.pollub.pari.payment.domain.PaymentStatus;
import pl.pollub.pari.payment.domain.PaymentType;
import pl.pollub.pari.payment.repository.PaymentRepository;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.repository.ReservationRepository;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.repository.UserRepository;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** Aggregated figures for the driver dashboard (PARI-48), computed on the server instead of in the browser. */
@Service
public class DriverAnalyticsService {

    /** A booking that was paid and has not been cancelled, rejected or expired. */
    private static final Set<ReservationStatus> BOOKINGS = EnumSet.of(
            ReservationStatus.PENDING_APPROVAL, ReservationStatus.CONFIRMED, ReservationStatus.IN_PROGRESS, ReservationStatus.COMPLETED);

    /** A booking that still has to be used or is being used. */
    private static final Set<ReservationStatus> ACTIVE = EnumSet.of(
            ReservationStatus.PENDING_APPROVAL, ReservationStatus.CONFIRMED, ReservationStatus.IN_PROGRESS);

    /**
     * {@code totalSpent} is what the driver really paid: successful charges and penalties minus successful refunds.
     * {@code totalSaved} is not defined by the requirements yet (the ticket gives no rule) and is always zero until
     * the team decides; it must not be filled with an invented percentage.
     */
    public record DriverAnalytics(Money totalSpent, Money totalSaved, int totalBookingsCount, int activeBookingsCount,
                                  int loyaltyPoints) {
    }

    private final ReservationRepository reservations;
    private final PaymentRepository payments;
    private final UserRepository users;

    public DriverAnalyticsService(ReservationRepository reservations, PaymentRepository payments, UserRepository users) {
        this.reservations = reservations;
        this.payments = payments;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public DriverAnalytics analytics(UUID driverId) {
        User driver = users.findById(driverId).orElseThrow(() -> new NotFoundException("User not found"));
        if (!driver.hasRole(Role.DRIVER)) {
            throw new ForbiddenException("The DRIVER role is required");
        }
        long spent = 0;
        for (Payment p : payments.findByDriverId(driverId, Pageable.unpaged())) {
            if (p.getStatus() != PaymentStatus.SUCCEEDED) {
                continue;
            }
            spent += p.getType() == PaymentType.REFUND ? -p.getAmount().amountMinor() : p.getAmount().amountMinor();
        }
        return new DriverAnalytics(Money.pln(Math.max(spent, 0)), Money.ZERO,
                (int) reservations.countByDriverIdAndStatusIn(driverId, BOOKINGS),
                (int) reservations.countByDriverIdAndStatusIn(driverId, ACTIVE),
                driver.getLoyaltyPoints());
    }
}
