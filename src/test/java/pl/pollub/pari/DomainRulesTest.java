package pl.pollub.pari;

import org.junit.jupiter.api.Test;
import pl.pollub.pari.common.domain.IllegalStateTransitionException;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.payment.domain.Payout;
import pl.pollub.pari.payment.domain.PayoutStatus;
import pl.pollub.pari.premium.domain.PremiumSubscription;
import pl.pollub.pari.review.domain.Review;
import pl.pollub.pari.review.domain.ReviewStatus;
import pl.pollub.pari.spot.domain.Address;
import pl.pollub.pari.spot.domain.Dimensions;
import pl.pollub.pari.spot.domain.GeoPoint;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.spot.domain.SpotStatus;
import pl.pollub.pari.support.domain.Report;
import pl.pollub.pari.support.domain.ReportStatus;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.UserStatus;
import pl.pollub.pari.verification.domain.VerificationRequest;
import pl.pollub.pari.verification.domain.VerificationStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Business rules that live inside entities. No database needed. */
class DomainRulesTest {

    private static final Instant NOW = Instant.parse("2030-01-07T10:00:00Z");

    private static User user(Role... roles) {
        return new User("someone@example.com", "hash", "Some", "One", "+48 600 000 000", Set.of(roles));
    }

    private static ParkingSpot spot(User owner) {
        return new ParkingSpot(owner, "Garage", null, new Address("Street 1", "Lublin", "20-001", "PL"),
                new GeoPoint(51.2465, 22.5684), new Dimensions(500, 250, 200), Money.pln(1000));
    }

    // ---- user

    @Test
    void emailIsStoredLowerCaseAndTrimmed() {
        assertThat(new User("  Anna@Example.COM ", "h", "A", "B", null, Set.of(Role.DRIVER)).getEmail()).isEqualTo("anna@example.com");
    }

    @Test
    void roleCanBeSwitchedOnlyToHeldRoles() {
        User both = user(Role.DRIVER, Role.OWNER);
        both.switchRole(Role.OWNER);
        assertThat(both.getActiveRole()).isEqualTo(Role.OWNER);

        User driver = user(Role.DRIVER);
        assertThatThrownBy(() -> driver.switchRole(Role.OWNER)).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void aUserKeepsAtLeastOneRole() {
        User driver = user(Role.DRIVER);
        assertThatThrownBy(() -> driver.revokeRole(Role.DRIVER)).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void blockAndUnblock() {
        User user = user(Role.DRIVER);
        user.block();
        assertThat(user.isBlocked()).isTrue();
        assertThat(user.getStatus()).isEqualTo(UserStatus.BLOCKED);
        user.unblock();
        assertThat(user.isBlocked()).isFalse();
    }

    @Test
    void loyaltyPointsCannotGoNegative() {
        User user = user(Role.DRIVER);
        user.addLoyaltyPoints(100);
        assertThatThrownBy(() -> user.addLoyaltyPoints(-101)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- parking spot

    @Test
    void spotNeedsOwnershipDeclarationBeforeActivation() {
        ParkingSpot spot = spot(user(Role.OWNER));
        assertThat(spot.getStatus()).isEqualTo(SpotStatus.DRAFT);
        assertThatThrownBy(spot::activate).isInstanceOf(IllegalStateTransitionException.class);

        spot.declareOwnership(NOW);
        spot.activate();
        assertThat(spot.isBookable()).isTrue();
    }

    @Test
    void blockedSpotCannotBeActivatedUntilUnblocked() {
        ParkingSpot spot = spot(user(Role.OWNER));
        spot.declareOwnership(NOW);
        spot.activate();
        spot.block();
        assertThat(spot.isBookable()).isFalse();
        assertThatThrownBy(spot::activate).isInstanceOf(IllegalStateTransitionException.class);

        spot.unblock();
        spot.activate();
        assertThat(spot.isBookable()).isTrue();
    }

    @Test
    void photosKeepTheirOrderAfterRemoval() {
        ParkingSpot spot = spot(user(Role.OWNER));
        spot.addPhoto("a.jpg");
        var b = spot.addPhoto("b.jpg");
        spot.addPhoto("c.jpg");

        spot.removePhoto(b);

        assertThat(spot.getPhotos()).extracting("storageKey").containsExactly("a.jpg", "c.jpg");
        assertThat(spot.getPhotos()).extracting("position").containsExactly(0, 1);
    }

    @Test
    void dimensionsDecideWhetherAVehicleFits() {
        Dimensions space = new Dimensions(500, 250, 200);
        assertThat(space.fits(new Dimensions(450, 180, 150))).isTrue();
        assertThat(space.fits(new Dimensions(520, 180, 150))).isFalse();
        assertThat(space.fits(new Dimensions(450, 180, 210))).isFalse();
        assertThat(new Dimensions(500, 250, null).fits(new Dimensions(450, 180, 400))).isTrue();
    }

    @Test
    void coordinatesAreValidated() {
        assertThatThrownBy(() -> new GeoPoint(91, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeoPoint(0, 181)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- verification

    @Test
    void approvalGivesTheVerifiedBadge() {
        User owner = user(Role.OWNER);
        ParkingSpot spot = spot(owner);
        VerificationRequest request = new VerificationRequest(spot, owner, NOW);

        request.claim(user(Role.VERIFIER));
        request.approve("Matches the description", NOW.plusSeconds(3600));

        assertThat(request.getStatus()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(spot.isVerified()).isTrue();
    }

    @Test
    void rejectionNeedsAReasonAndLeavesTheSpotUnverified() {
        User owner = user(Role.OWNER);
        ParkingSpot spot = spot(owner);
        VerificationRequest request = new VerificationRequest(spot, owner, NOW);
        request.claim(user(Role.VERIFIER));

        assertThatThrownBy(() -> request.reject(" ", NOW)).isInstanceOf(IllegalArgumentException.class);
        request.reject("Space is smaller than declared", NOW);

        assertThat(request.getStatus()).isEqualTo(VerificationStatus.REJECTED);
        assertThat(spot.isVerified()).isFalse();
    }

    @Test
    void unclaimedRequestCannotBeDecided() {
        User owner = user(Role.OWNER);
        VerificationRequest request = new VerificationRequest(spot(owner), owner, NOW);

        assertThatThrownBy(() -> request.approve("x", NOW)).isInstanceOf(IllegalStateTransitionException.class);
    }

    // ---- payout

    @Test
    void payoutNetIsGrossMinusCommissionAndIsDueAfterReleaseTime() {
        Payout payout = new Payout(null, user(Role.OWNER), Money.pln(10_000), Money.pln(1_000), NOW.plus(Duration.ofHours(24)));

        assertThat(payout.getNet()).isEqualTo(Money.pln(9_000));
        assertThat(payout.isDue(NOW)).isFalse();
        assertThat(payout.isDue(NOW.plus(Duration.ofHours(24)))).isTrue();
    }

    @Test
    void heldPayoutIsNotDueUntilResumed() {
        Payout payout = new Payout(null, user(Role.OWNER), Money.pln(10_000), Money.pln(1_000), NOW);
        payout.hold();
        assertThat(payout.isDue(NOW)).isFalse();

        payout.resume();
        payout.markPaid(NOW, "tr_123");
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PAID);
        assertThatThrownBy(payout::cancel).isInstanceOf(IllegalStateTransitionException.class);
    }

    // ---- review, report, premium

    @Test
    void ratingMustBeBetweenOneAndFive() {
        assertThatThrownBy(() -> new Review(null, null, null, null, 0, "bad")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Review(null, null, null, null, 6, "great")).isInstanceOf(IllegalArgumentException.class);

        Review review = new Review(null, null, null, null, 5, "great");
        review.hide();
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.HIDDEN);
    }

    @Test
    void reportLifecycle() {
        Report report = new Report(null, user(Role.DRIVER), null, "SPACE_OCCUPIED", "Another car was there");
        assertThat(report.isOpen()).isTrue();

        report.assignTo(user(Role.SUPPORT));
        assertThat(report.getStatus()).isEqualTo(ReportStatus.IN_PROGRESS);

        report.resolve("Refunded");
        assertThatThrownBy(() -> report.assignTo(user(Role.SUPPORT))).isInstanceOf(IllegalStateTransitionException.class);

        report.close(NOW);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.CLOSED);
        assertThatThrownBy(() -> report.close(NOW)).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void cancelledPremiumStaysActiveUntilItExpires() {
        PremiumSubscription premium = new PremiumSubscription(user(Role.OWNER), PremiumSubscription.MONTHLY, NOW,
                NOW.plus(Duration.ofDays(30)), "sub_1");

        premium.cancel();

        assertThat(premium.isActive(NOW.plus(Duration.ofDays(10)))).isTrue();
        assertThat(premium.isActive(NOW.plus(Duration.ofDays(31)))).isFalse();

        premium.expire();
        assertThat(premium.isActive(NOW.plus(Duration.ofDays(10)))).isFalse();
    }
}
