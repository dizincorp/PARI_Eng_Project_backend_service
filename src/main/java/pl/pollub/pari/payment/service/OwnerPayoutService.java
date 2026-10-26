package pl.pollub.pari.payment.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.ForbiddenException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.common.service.AuditTrail;
import pl.pollub.pari.payment.domain.PayoutStatus;
import pl.pollub.pari.payment.domain.Withdrawal;
import pl.pollub.pari.payment.gateway.PaymentGateway;
import pl.pollub.pari.payment.repository.PayoutRepository;
import pl.pollub.pari.payment.repository.WithdrawalRepository;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.repository.UserRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The owner's money (F17, F18): the balance built from payouts, and withdrawals of released money.
 *
 * <p>Jira names the escrow states ESCROW_HOLD and RELEASED_TO_OWNER; they map onto {@link PayoutStatus}: SCHEDULED and
 * HELD are ESCROW_HOLD (pending), PAID is RELEASED_TO_OWNER (available until withdrawn). CANCELLED payouts count nowhere.
 */
@Service
public class OwnerPayoutService {

    /** {@code availableBalance} = released payouts minus completed withdrawals; {@code totalEarned} ignores withdrawals. */
    public record Balance(Money availableBalance, Money pendingEscrow, Money totalEarned, Money platformFeesPaid) {
    }

    private final PayoutRepository payouts;
    private final WithdrawalRepository withdrawals;
    private final UserRepository users;
    private final PaymentGateway gateway;
    private final AuditTrail audit;

    public OwnerPayoutService(PayoutRepository payouts, WithdrawalRepository withdrawals, UserRepository users,
                              PaymentGateway gateway, AuditTrail audit) {
        this.payouts = payouts;
        this.withdrawals = withdrawals;
        this.users = users;
        this.gateway = gateway;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Balance balance(UUID ownerId) {
        long released = payouts.sumNetMinor(ownerId, List.of(PayoutStatus.PAID.name()));
        long pending = payouts.sumNetMinor(ownerId, List.of(PayoutStatus.SCHEDULED.name(), PayoutStatus.HELD.name()));
        long fees = payouts.sumCommissionMinor(ownerId, PayoutStatus.PAID.name());
        long withdrawn = withdrawals.sumCompletedMinor(ownerId);
        return new Balance(Money.pln(released - withdrawn), Money.pln(pending), Money.pln(released), Money.pln(fees));
    }

    /** The owner's withdrawals, newest first (empty when there are none). Only owners have a withdrawal history. */
    @Transactional(readOnly = true)
    public List<Withdrawal> history(UUID ownerId) {
        User owner = users.findById(ownerId).orElseThrow(() -> new NotFoundException("User not found"));
        if (!owner.hasRole(Role.OWNER)) {
            throw new ForbiddenException("Only an owner has a withdrawal history");
        }
        return withdrawals.findByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    /**
     * Withdraws {@code amount} (PLN, at most two decimals) to the IBAN through the payment gateway. No fee and no limit
     * is applied: the plan defines none. Throws {@link PaymentFailedException} when the provider refuses (nothing is saved).
     */
    @Transactional
    public Withdrawal withdraw(UUID ownerId, BigDecimal amount, String iban) {
        User owner = users.findById(ownerId).orElseThrow(() -> new NotFoundException("User not found"));
        if (!owner.hasRole(Role.OWNER) || owner.isBlocked()) {
            throw new ForbiddenException("Only an active owner can withdraw money");
        }
        Money money = toMoney(amount);
        String normalizedIban = Iban.normalize(iban);

        withdrawals.lockOwner(ownerId);
        Money available = balance(ownerId).availableBalance();
        if (money.compareTo(available) > 0) {
            throw new ConflictException("INSUFFICIENT_BALANCE", "The amount exceeds the available balance");
        }

        PaymentGateway.Result result = gateway.payout(money, normalizedIban, "withdrawal-" + UUID.randomUUID());
        if (!result.succeeded()) {
            throw new PaymentFailedException("Withdrawal failed: " + result.failureReason());
        }
        Withdrawal withdrawal = withdrawals.save(new Withdrawal(owner, money, normalizedIban, result.providerRef()));
        audit.record(ownerId, "Withdrawal", withdrawal.getId(), "WITHDRAWN",
                Map.of("amountMinor", money.amountMinor(), "iban", withdrawal.maskedIban()));
        return withdrawal;
    }

    private static Money toMoney(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("The amount must be positive");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("The amount can have at most two decimal places");
        }
        return Money.pln(amount.movePointRight(2).longValueExact());
    }
}
