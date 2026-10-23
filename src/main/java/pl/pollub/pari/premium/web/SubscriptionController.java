package pl.pollub.pari.premium.web;

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
import pl.pollub.pari.premium.domain.PremiumPlan;
import pl.pollub.pari.premium.domain.PremiumSubscription;
import pl.pollub.pari.premium.service.PremiumService;

import java.time.Instant;
import java.util.UUID;

/** Owner Premium subscription (PARI-23). */
@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    /** {@code paymentMethodToken} is the provider's payment method id; with the mock gateway pm_card_visa succeeds. */
    public record SubscribeRequest(@NotNull PremiumPlan plan, @NotBlank String paymentMethodToken) {
    }

    /** {@code plan} and {@code expiresAt} are null when the owner is not Premium. */
    public record StatusResponse(boolean isPremium, PremiumPlan plan, Instant expiresAt) {

        static StatusResponse of(PremiumSubscription s) {
            return new StatusResponse(true, PremiumPlan.fromCode(s.getPlan()), s.getExpiresAt());
        }
    }

    private final PremiumService premium;

    public SubscriptionController(PremiumService premium) {
        this.premium = premium;
    }

    @PostMapping("/premium")
    @ResponseStatus(HttpStatus.CREATED)
    public StatusResponse subscribe(@CurrentUserId UUID userId, @Valid @RequestBody SubscribeRequest r) {
        return StatusResponse.of(premium.subscribe(userId, r.plan(), r.paymentMethodToken()));
    }

    @GetMapping("/my-status")
    public StatusResponse myStatus(@CurrentUserId UUID userId) {
        return premium.status(userId).subscription()
                .map(StatusResponse::of)
                .orElse(new StatusResponse(false, null, null));
    }
}
