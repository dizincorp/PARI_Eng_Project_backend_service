package pl.pollub.pari.payment.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.pollub.pari.auth.security.CurrentUserId;
import pl.pollub.pari.payment.domain.Withdrawal;
import pl.pollub.pari.payment.service.OwnerPayoutService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Owner balance and withdrawals (F17, F18). */
@RestController
@RequestMapping("/api/owner/payouts")
public class OwnerPayoutController {

    public record BalanceResponse(BigDecimal availableBalance, BigDecimal pendingEscrow, String currency,
                                  BigDecimal totalEarned, BigDecimal platformFeesPaid) {
    }

    public record WithdrawRequest(@NotNull BigDecimal amount, @NotBlank String iban) {
    }

    /** {@code iban} is masked to its last four characters. */
    public record WithdrawalResponse(UUID id, BigDecimal amount, String currency, String status, String iban,
                                     Instant createdAt, BigDecimal availableBalance) {
    }

    private final OwnerPayoutService payouts;

    public OwnerPayoutController(OwnerPayoutService payouts) {
        this.payouts = payouts;
    }

    @GetMapping("/balance")
    public BalanceResponse balance(@CurrentUserId UUID userId) {
        OwnerPayoutService.Balance b = payouts.balance(userId);
        return new BalanceResponse(b.availableBalance().toDecimal(), b.pendingEscrow().toDecimal(),
                b.availableBalance().currency(), b.totalEarned().toDecimal(), b.platformFeesPaid().toDecimal());
    }

    /**
     * The owner's withdrawals, newest first; an empty list when there are none. Every element carries the balance that is
     * available now. The IBAN is masked here as everywhere else.
     */
    @GetMapping({"/history", "/withdrawals"})
    public List<WithdrawalResponse> history(@CurrentUserId UUID userId) {
        List<Withdrawal> list = payouts.history(userId);
        BigDecimal available = payouts.balance(userId).availableBalance().toDecimal();
        return list.stream().map(w -> toResponse(w, available)).toList();
    }

    @PostMapping("/withdraw")
    @ResponseStatus(HttpStatus.CREATED)
    public WithdrawalResponse withdraw(@CurrentUserId UUID userId, @Valid @RequestBody WithdrawRequest r) {
        Withdrawal w = payouts.withdraw(userId, r.amount(), r.iban());
        return toResponse(w, payouts.balance(userId).availableBalance().toDecimal());
    }

    private static WithdrawalResponse toResponse(Withdrawal w, BigDecimal availableBalance) {
        return new WithdrawalResponse(w.getId(), w.getAmount().toDecimal(), w.getAmount().currency(),
                w.getStatus().name(), w.maskedIban(), w.getCreatedAt(), availableBalance);
    }
}
