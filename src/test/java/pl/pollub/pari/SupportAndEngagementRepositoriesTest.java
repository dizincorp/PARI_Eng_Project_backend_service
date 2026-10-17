package pl.pollub.pari;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import pl.pollub.pari.availability.domain.AvailabilityException;
import pl.pollub.pari.availability.domain.AvailabilityRule;
import pl.pollub.pari.availability.domain.ExceptionKind;
import pl.pollub.pari.availability.repository.AvailabilityExceptionRepository;
import pl.pollub.pari.availability.repository.AvailabilityRuleRepository;
import pl.pollub.pari.common.domain.TimeRange;
import pl.pollub.pari.notification.domain.Notification;
import pl.pollub.pari.notification.domain.NotificationChannel;
import pl.pollub.pari.notification.repository.NotificationRepository;
import pl.pollub.pari.premium.domain.PremiumSubscription;
import pl.pollub.pari.premium.repository.PremiumSubscriptionRepository;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.review.domain.Review;
import pl.pollub.pari.review.domain.ReviewStatus;
import pl.pollub.pari.review.repository.ReviewRepository;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.support.DbTestBase;
import pl.pollub.pari.support.domain.Report;
import pl.pollub.pari.support.domain.ReportStatus;
import pl.pollub.pari.support.domain.Sanction;
import pl.pollub.pari.support.domain.SanctionType;
import pl.pollub.pari.support.repository.ReportRepository;
import pl.pollub.pari.support.repository.SanctionRepository;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;
import pl.pollub.pari.verification.domain.VerificationRequest;
import pl.pollub.pari.verification.domain.VerificationStatus;
import pl.pollub.pari.verification.repository.VerificationRequestRepository;

import java.time.Duration;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reviews, reports, verification, availability, notifications and Premium: the smaller repositories in one place. */
class SupportAndEngagementRepositoriesTest extends DbTestBase {

    @Autowired ReviewRepository reviews;
    @Autowired ReportRepository reports;
    @Autowired SanctionRepository sanctions;
    @Autowired VerificationRequestRepository verifications;
    @Autowired AvailabilityRuleRepository rules;
    @Autowired AvailabilityExceptionRepository exceptions;
    @Autowired NotificationRepository notifications;
    @Autowired PremiumSubscriptionRepository premium;

    User owner;
    User driver;
    User support;
    ParkingSpot spot;
    Reservation reservation;

    @BeforeEach
    void setUp() {
        owner = persistUser(Role.OWNER);
        driver = persistUser(Role.DRIVER);
        support = persistUser(Role.SUPPORT);
        Vehicle vehicle = persistVehicle(driver);
        spot = persistSpot(owner, 51.2465, 22.5684, 1000);
        reservation = persistReservation(spot, driver, vehicle, T10, T12);
    }

    // ---- reviews

    @Test
    void oneReviewPerAuthorAndReservation() {
        reviews.saveAndFlush(new Review(reservation, driver, owner, spot, 5, "Great"));

        assertThat(reviews.existsByReservationIdAndAuthorId(reservation.getId(), driver.getId())).isTrue();
        assertThat(reviews.existsByReservationIdAndAuthorId(reservation.getId(), owner.getId())).isFalse();
        assertThatThrownBy(() -> reviews.saveAndFlush(new Review(reservation, driver, owner, spot, 4, "Again")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ratingAveragesIgnoreHiddenReviews() {
        reviews.save(new Review(reservation, driver, owner, spot, 5, "Great"));
        Review rude = reviews.save(new Review(reservation, owner, driver, null, 1, "Rude"));
        reviews.save(new Review(persistReservationAt(T12, T14), driver, owner, spot, 3, "OK"));
        flushAndClear();

        assertThat(reviews.averageRatingForSpot(spot.getId())).isEqualTo(4.0);
        assertThat(reviews.countVisibleForSpot(spot.getId())).isEqualTo(2);

        reviews.findById(rude.getId()).orElseThrow().hide();
        flushAndClear();
        assertThat(reviews.averageRatingForUser(driver.getId())).isNull();
        assertThat(reviews.findBySpotIdAndStatusOrderByCreatedAtDesc(spot.getId(), ReviewStatus.VISIBLE, PageRequest.of(0, 10)))
                .hasSize(2);
        assertThat(reviews.findByStatusOrderByCreatedAtDesc(ReviewStatus.HIDDEN, PageRequest.of(0, 10))).hasSize(1);
    }

    // ---- reports and sanctions

    @Test
    void supportSearchFiltersAreOptional() {
        Report open = reports.save(new Report(reservation, driver, owner, "SPACE_OCCUPIED", "Another car"));
        Report taken = reports.save(new Report(reservation, owner, driver, "PAYMENT", "Dispute"));
        taken.assignTo(support);
        flushAndClear();

        PageRequest page = PageRequest.of(0, 10);
        assertThat(reports.search(null, null, null, page).getTotalElements()).isEqualTo(2);
        assertThat(reports.search(ReportStatus.OPEN, null, null, page)).extracting(Report::getId).containsExactly(open.getId());
        assertThat(reports.search(null, "PAYMENT", null, page)).extracting(Report::getId).containsExactly(taken.getId());
        assertThat(reports.search(null, null, support.getId(), page)).extracting(Report::getId).containsExactly(taken.getId());
        assertThat(reports.findInvolving(driver.getId(), page).getTotalElements()).isEqualTo(2);
        assertThat(reports.existsByReservationIdAndStatusIn(reservation.getId(), EnumSet.of(ReportStatus.OPEN, ReportStatus.IN_PROGRESS))).isTrue();
        assertThat(reports.existsByReservationIdAndStatusIn(reservation.getId(), EnumSet.of(ReportStatus.CLOSED))).isFalse();
    }

    @Test
    void reportMessagesKeepTheirOrderAndAreRemovedWithTheReport() {
        Report report = new Report(reservation, driver, owner, "SPACE_OCCUPIED", "Another car");
        report.addMessage(support, "Please explain", false);
        report.addMessage(support, "Owner seems unresponsive", true);
        reports.saveAndFlush(report);
        flushAndClear();

        Report loaded = reports.findById(report.getId()).orElseThrow();
        assertThat(loaded.getMessages()).extracting("body").containsExactly("Please explain", "Owner seems unresponsive");
        assertThat(loaded.getMessages()).extracting("internalNote").containsExactly(false, true);

        reports.delete(loaded);
        flushAndClear();
        assertThat(jdbc.queryForObject("select count(*) from report_messages", Integer.class)).isZero();
    }

    @Test
    void sanctionsAreCountedPerType() {
        Report report = reports.save(new Report(reservation, driver, owner, "SPACE_OCCUPIED", "x"));
        sanctions.save(new Sanction(owner, report, support, SanctionType.WARNING, "first"));
        sanctions.save(new Sanction(owner, report, support, SanctionType.WARNING, "second"));
        sanctions.save(new Sanction(owner, null, support, SanctionType.BLOCK, "limit exceeded"));
        flushAndClear();

        assertThat(sanctions.countByUserIdAndType(owner.getId(), SanctionType.WARNING)).isEqualTo(2);
        assertThat(sanctions.findByUserIdOrderByCreatedAtDesc(owner.getId())).hasSize(3);
    }

    // ---- verification

    @Test
    void verifierQueueIsOldestFirstAndApprovalPersistsTheBadge() {
        VerificationRequest first = verifications.save(new VerificationRequest(spot, owner, T10));
        ParkingSpot second = persistSpot(owner, 51.25, 22.57, 1000);
        verifications.save(new VerificationRequest(second, owner, T12));
        flushAndClear();

        var queue = verifications.findByStatusInOrderByRequestedAtAsc(EnumSet.of(VerificationStatus.REQUESTED), PageRequest.of(0, 10));
        assertThat(queue.getContent()).hasSize(2);
        assertThat(queue.getContent().get(0).getId()).isEqualTo(first.getId());
        assertThat(verifications.existsBySpotIdAndStatusIn(spot.getId(), EnumSet.of(VerificationStatus.REQUESTED))).isTrue();

        VerificationRequest request = verifications.findById(first.getId()).orElseThrow();
        request.claim(persistUser(Role.VERIFIER));
        request.approve("OK", T14);
        flushAndClear();

        assertThat(em.find(ParkingSpot.class, spot.getId()).isVerified()).isTrue();
        assertThat(verifications.findBySpotIdOrderByRequestedAtDesc(spot.getId())).extracting("status").containsExactly(VerificationStatus.APPROVED);
    }

    // ---- availability

    @Test
    void weeklyRulesAreOrderedAndCanBeReplaced() {
        rules.save(new AvailabilityRule(spot, 2, LocalTime.of(8, 0), LocalTime.of(12, 0), null, null));
        rules.save(new AvailabilityRule(spot, 1, LocalTime.of(14, 0), LocalTime.of(18, 0), null, null));
        rules.save(new AvailabilityRule(spot, 1, LocalTime.of(8, 0), LocalTime.of(12, 0), null, null));
        flushAndClear();

        assertThat(rules.findBySpotIdOrderByDayOfWeekAscStartTimeAsc(spot.getId()))
                .extracting(r -> r.getDayOfWeek() + "@" + r.getStartTime()).containsExactly("1@08:00", "1@14:00", "2@08:00");
        assertThat(rules.findBySpotIdAndDayOfWeek(spot.getId(), 1)).hasSize(2);

        assertThat(rules.deleteAllBySpotId(spot.getId())).isEqualTo(3);
    }

    @Test
    void blockedSlotsAreFoundByOverlap() {
        exceptions.save(new AvailabilityException(spot, new TimeRange(T10, T12), ExceptionKind.BLOCKED, "Private use"));
        flushAndClear();

        assertThat(exceptions.findOverlapping(spot.getId(), T10.plusSeconds(60), T14)).hasSize(1);
        assertThat(exceptions.findOverlapping(spot.getId(), T12, T14)).isEmpty();
        assertThat(exceptions.existsOverlapping(spot.getId(), ExceptionKind.BLOCKED, T10, T12)).isTrue();
        assertThat(exceptions.existsOverlapping(spot.getId(), ExceptionKind.OPEN, T10, T12)).isFalse();
    }

    @Test
    void availabilityIsRemovedWithItsSpot() {
        rules.save(new AvailabilityRule(spot, 1, LocalTime.of(8, 0), LocalTime.of(12, 0), null, null));
        exceptions.save(new AvailabilityException(spot, new TimeRange(T10, T12), ExceptionKind.BLOCKED, null));
        em.flush();

        jdbc.update("delete from payments where reservation_id = ?", reservation.getId());
        jdbc.update("delete from reservations where spot_id = ?", spot.getId());
        jdbc.update("delete from parking_spots where id = ?", spot.getId());

        assertThat(jdbc.queryForObject("select count(*) from availability_rules", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from availability_exceptions", Integer.class)).isZero();
    }

    // ---- notifications

    @Test
    void notificationsStoreJsonAndTrackUnreadCount() {
        Notification n1 = notifications.save(new Notification(owner, "RESERVATION_CREATED", "New reservation", null,
                Map.of("reservationCode", "RSV-1", "nights", 0), NotificationChannel.IN_APP));
        notifications.save(new Notification(owner, "RESERVATION_CREATED", "Another", null, Map.of(), NotificationChannel.IN_APP));
        notifications.save(new Notification(owner, "RESERVATION_CREATED", "Mail copy", null, Map.of(), NotificationChannel.EMAIL));
        flushAndClear();

        assertThat(notifications.countByUserIdAndChannelAndReadAtIsNull(owner.getId(), NotificationChannel.IN_APP)).isEqualTo(2);
        assertThat(notifications.findByIdAndUserId(n1.getId(), owner.getId()).orElseThrow().getPayload()).containsEntry("reservationCode", "RSV-1");
        assertThat(notifications.findByIdAndUserId(n1.getId(), driver.getId())).isEmpty();

        notifications.findById(n1.getId()).orElseThrow().markRead(T10);
        flushAndClear();
        assertThat(notifications.findByUserIdAndChannelAndReadAtIsNullOrderByCreatedAtDesc(owner.getId(), NotificationChannel.IN_APP, PageRequest.of(0, 10)))
                .hasSize(1);

        assertThat(notifications.markAllRead(owner.getId(), NotificationChannel.IN_APP, T12)).isEqualTo(1);
        assertThat(notifications.findByUserIdAndChannelOrderByCreatedAtDesc(owner.getId(), NotificationChannel.IN_APP, PageRequest.of(0, 10)))
                .hasSize(2);
    }

    // ---- premium

    @Test
    void currentPremiumPlanIncludesCancelledOnesUntilTheyExpire() {
        PremiumSubscription plan = premium.save(new PremiumSubscription(owner, PremiumSubscription.MONTHLY, T10, T10.plus(Duration.ofDays(30)), "sub_1"));
        plan.cancel();
        flushAndClear();

        assertThat(premium.findCurrentOne(owner.getId(), T10.plus(Duration.ofDays(5)))).isPresent();
        assertThat(premium.findCurrentOne(owner.getId(), T10.plus(Duration.ofDays(31)))).isEmpty();
        assertThat(premium.findCurrentOne(driver.getId(), T10.plus(Duration.ofDays(5)))).isEmpty();
        assertThat(premium.findDueForExpiry(T10.plus(Duration.ofDays(31)))).hasSize(1);
        assertThat(premium.findDueForExpiry(T10.plus(Duration.ofDays(5)))).isEmpty();
    }

    private Reservation persistReservationAt(java.time.Instant start, java.time.Instant end) {
        return persistReservation(spot, driver, persistVehicle(driver), start, end);
    }
}
