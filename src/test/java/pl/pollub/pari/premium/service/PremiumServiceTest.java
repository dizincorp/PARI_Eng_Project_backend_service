package pl.pollub.pari.premium.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.domain.TimeRange;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.ForbiddenException;
import pl.pollub.pari.payment.gateway.MockPaymentGateway;
import pl.pollub.pari.payment.service.PaymentFailedException;
import pl.pollub.pari.premium.domain.PremiumPlan;
import pl.pollub.pari.premium.domain.PremiumSubscription;
import pl.pollub.pari.reservation.service.PricingService;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.support.ServiceTestBase;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(PremiumService.class)
class PremiumServiceTest extends ServiceTestBase {

    @Autowired
    PremiumService service;
    @Autowired
    PricingService pricing;

    User owner;
    ParkingSpot spot;

    @BeforeEach
    void setUp() {
        owner = persistUser(Role.OWNER);
        spot = persistSpot(owner, 51.2, 22.5, 1000);
        em.flush();
    }

    @Test
    void monthlyPlanLastsOneMonthAndYearlyOneYear() {
        PremiumSubscription monthly = service.subscribe(owner.getId(), PremiumPlan.MONTHLY, "pm_card_visa");

        assertThat(monthly.getPlan()).isEqualTo("PREMIUM_MONTHLY");
        assertThat(monthly.getStartedAt()).isEqualTo(NOW);
        assertThat(monthly.getExpiresAt()).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));

        User other = persistUser(Role.OWNER);
        em.flush();
        PremiumSubscription yearly = service.subscribe(other.getId(), PremiumPlan.YEARLY, "pm_card_visa");
        assertThat(yearly.getExpiresAt()).isEqualTo(Instant.parse("2030-12-01T00:00:00Z"));
    }

    @Test
    void statusIsNotPremiumUntilSubscribed() {
        assertThat(service.status(owner.getId()).isPremium()).isFalse();

        service.subscribe(owner.getId(), PremiumPlan.MONTHLY, "pm_card_visa");

        assertThat(service.status(owner.getId()).isPremium()).isTrue();
        assertThat(service.isPremium(owner.getId())).isTrue();
    }

    @Test
    void anExpiredPlanDoesNotCount() {
        em.persist(new PremiumSubscription(owner, PremiumPlan.MONTHLY.code(), NOW.minusSeconds(7200), NOW.minusSeconds(1), "sub_old"));
        em.flush();

        assertThat(service.status(owner.getId()).isPremium()).isFalse();
    }

    @Test
    void anOwnerWithAnActivePlanCannotBuyAnother() {
        service.subscribe(owner.getId(), PremiumPlan.MONTHLY, "pm_card_visa");

        assertThatThrownBy(() -> service.subscribe(owner.getId(), PremiumPlan.YEARLY, "pm_card_visa"))
                .isInstanceOf(ConflictException.class).extracting("errorCode").isEqualTo("ALREADY_PREMIUM");
    }

    @Test
    void aDeclinedCardCreatesNoSubscription() {
        assertThatThrownBy(() -> service.subscribe(owner.getId(), PremiumPlan.MONTHLY, MockPaymentGateway.DECLINED_TOKEN))
                .isInstanceOf(PaymentFailedException.class);

        assertThat(service.status(owner.getId()).isPremium()).isFalse();
    }

    @Test
    void onlyOwnersCanSubscribe() {
        User driver = persistUser(Role.DRIVER);
        em.flush();

        assertThatThrownBy(() -> service.subscribe(driver.getId(), PremiumPlan.MONTHLY, "pm_card_visa"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void premiumOwnerKeepsTheWholeSpotCostAndOthersPayTheStandardCommission() {
        TimeRange range = new TimeRange(Instant.parse("2030-01-07T10:00:00Z"), Instant.parse("2030-01-07T12:00:00Z"));
        assertThat(pricing.quote(spot, range).commission()).isEqualTo(Money.pln(200));

        service.subscribe(owner.getId(), PremiumPlan.MONTHLY, "pm_card_visa");

        PricingService.Quote quote = pricing.quote(spot, range);
        assertThat(quote.commission()).isEqualTo(Money.ZERO);
        assertThat(quote.spotCost()).isEqualTo(Money.pln(2000));
    }
}
