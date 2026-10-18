package pl.pollub.pari.payment.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.service.PlatformSettings;
import pl.pollub.pari.payment.domain.Payment;
import pl.pollub.pari.payment.domain.PaymentStatus;
import pl.pollub.pari.payment.domain.PaymentType;
import pl.pollub.pari.payment.domain.Payout;
import pl.pollub.pari.payment.domain.PayoutStatus;
import pl.pollub.pari.payment.gateway.PaymentGateway;
import pl.pollub.pari.payment.repository.PaymentRepository;
import pl.pollub.pari.payment.repository.PayoutRepository;
import pl.pollub.pari.reservation.domain.Reservation;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Money movements of a reservation: the charge when it is booked, the refund when it is cancelled or rejected, and the
 * owner's payout that waits in soft escrow (F17) until a delay after the reservation ends.
 */
@Service
public class PaymentService {

    private final PaymentGateway gateway;
    private final PaymentRepository payments;
    private final PayoutRepository payouts;
    private final PlatformSettings settings;

    public PaymentService(PaymentGateway gateway, PaymentRepository payments, PayoutRepository payouts,
                          PlatformSettings settings) {
        this.gateway = gateway;
        this.payments = payments;
        this.payouts = payouts;
        this.settings = settings;
    }

    /** Result of a charge attempt. The {@link Payment} row is saved in both cases, so failures stay on record. */
    public record ChargeOutcome(Payment payment, boolean succeeded, String failureReason) {
    }

    /** Charges the full reservation total. Does not throw when the provider declines; the caller decides what to do. */
    @Transactional
    public ChargeOutcome charge(Reservation reservation, String paymentMethodToken) {
        PaymentGateway.Result result = gateway.charge(reservation.getTotal(), paymentMethodToken,
                "reservation-" + reservation.getId());
        Payment payment = new Payment(reservation, PaymentType.CHARGE, reservation.getTotal(), result.providerRef());
        if (result.succeeded()) {
            payment.markSucceeded();
        } else {
            payment.markFailed();
        }
        payments.save(payment);
        return new ChargeOutcome(payment, result.succeeded(), result.failureReason());
    }

    /** Schedules the owner's payout: spot cost minus commission, released {@code payout_delay_hours} after the end (F17, F18). */
    @Transactional
    public Payout schedulePayout(Reservation reservation) {
        Instant releaseAt = reservation.getPeriod().end()
                .plus(Duration.ofHours(settings.integer("payout_delay_hours")));
        return payouts.save(new Payout(reservation, reservation.getSpot().getOwner(), reservation.getSpotCost(),
                reservation.getCommission(), releaseAt));
    }

    /**
     * Returns the whole charge to the driver and cancels the owner's payout. Does nothing when the reservation was never
     * paid. Throws {@link PaymentFailedException} when the provider refuses, which rolls back the surrounding transaction.
     *
     * @return the refund, or empty when there was nothing to refund
     */
    @Transactional
    public Optional<Payment> refundInFull(Reservation reservation) {
        Optional<Payment> charge = payments.findByReservationIdOrderByCreatedAtAsc(reservation.getId()).stream()
                .filter(p -> p.getType() == PaymentType.CHARGE && p.getStatus() == PaymentStatus.SUCCEEDED)
                .findFirst();
        if (charge.isEmpty()) {
            return Optional.empty();
        }
        Money amount = charge.get().getAmount();
        PaymentGateway.Result result = gateway.refund(charge.get().getProviderRef(), amount);
        if (!result.succeeded()) {
            throw new PaymentFailedException("Refund failed: " + result.failureReason());
        }
        Payment refund = new Payment(reservation, PaymentType.REFUND, amount, result.providerRef());
        refund.markSucceeded();
        payments.save(refund);

        payouts.findByReservationId(reservation.getId())
                .filter(p -> p.getStatus() == PayoutStatus.SCHEDULED || p.getStatus() == PayoutStatus.HELD)
                .ifPresent(Payout::cancel);
        return Optional.of(refund);
    }
}
