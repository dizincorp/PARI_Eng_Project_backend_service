package pl.pollub.pari.reservation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pl.pollub.pari.notification.service.NotificationService;
import pl.pollub.pari.notification.service.NotificationView;
import pl.pollub.pari.payment.domain.Payment;
import pl.pollub.pari.payment.domain.PaymentStatus;
import pl.pollub.pari.payment.domain.PaymentType;
import pl.pollub.pari.payment.domain.PayoutStatus;
import pl.pollub.pari.payment.repository.PaymentRepository;
import pl.pollub.pari.payment.repository.PayoutRepository;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.service.ReservationService.BookCommand;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.support.ServiceTestBase;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** PARI-38: unanswered requests expire after 60 minutes (platform setting) or when the start passes, with a full refund. */
class ReservationExpiryServiceTest extends ServiceTestBase {

    @Autowired
    ReservationService reservations;
    @Autowired
    ReservationExpiryService expiry;
    @Autowired
    PaymentRepository payments;
    @Autowired
    PayoutRepository payouts;
    @Autowired
    NotificationService notifications;

    User owner;
    User driver;
    Vehicle vehicle;
    ParkingSpot spot;

    @BeforeEach
    void setUp() {
        owner = persistUser(Role.OWNER);
        driver = persistUser(Role.DRIVER);
        vehicle = persistVehicle(driver);
        spot = persistSpot(owner, 51.2465, 22.5684, 1000);
        spot.setAutoAccept(false);
        em.flush();
    }

    @Test
    void aRequestOlderThanTheTimeoutExpiresWithAFullRefundAndNotifiesBoth() {
        UUID id = pendingRequest(T10, T12, NOW.minus(Duration.ofMinutes(61)));

        assertThat(expiry.findDueIds()).containsExactly(id);
        assertThat(expiry.expire(id)).isTrue();

        assertThat(reservations.get(driver.getId(), id).status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(payments.findByReservationIdOrderByCreatedAtAsc(id))
                .extracting(Payment::getType, Payment::getStatus)
                .containsExactly(tuple(PaymentType.CHARGE, PaymentStatus.SUCCEEDED), tuple(PaymentType.REFUND, PaymentStatus.SUCCEEDED));
        assertThat(payouts.findByReservationId(id).orElseThrow().getStatus()).isEqualTo(PayoutStatus.CANCELLED);
        em.flush();
        assertThat(types(driver)).containsExactly("RESERVATION_EXPIRED");
        assertThat(types(owner)).containsExactlyInAnyOrder("NEW_RESERVATION_REQUEST", "RESERVATION_EXPIRED");
    }

    @Test
    void aRequestWithinTheTimeoutAndBeforeItsStartIsLeftAlone() {
        UUID id = pendingRequest(T10, T12, NOW.minus(Duration.ofMinutes(30)));

        assertThat(expiry.findDueIds()).doesNotContain(id);
        assertThat(expiry.expire(id)).isFalse();
        assertThat(reservations.get(driver.getId(), id).status()).isEqualTo(ReservationStatus.PENDING_APPROVAL);
    }

    @Test
    void aRequestExpiresWhenItsStartHasPassedEvenIfItIsYoung() {
        UUID id = pendingRequest(NOW.minusSeconds(60), NOW.plusSeconds(3600), NOW.minus(Duration.ofMinutes(5)));

        assertThat(expiry.findDueIds()).containsExactly(id);
        assertThat(expiry.expire(id)).isTrue();
    }

    @Test
    void anAcceptedRequestIsNeverExpired() {
        UUID id = pendingRequest(T10, T12, NOW.minus(Duration.ofMinutes(61)));
        reservations.accept(owner.getId(), id);

        assertThat(expiry.findDueIds()).isEmpty();
        assertThat(expiry.expire(id)).isFalse();
        assertThat(reservations.get(driver.getId(), id).status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void anExpiredRequestFreesTheSlotForAnotherBooking() {
        UUID id = pendingRequest(T10, T12, NOW.minus(Duration.ofMinutes(61)));
        expiry.expire(id);
        em.flush();

        User other = persistUser(Role.DRIVER);
        ReservationView second = reservations.book(other.getId(),
                new BookCommand(spot.getId(), persistVehicle(other).getId(), T10, T12, "pm_card_visa")).reservation();

        assertThat(second.status()).isEqualTo(ReservationStatus.PENDING_APPROVAL);
    }

    private UUID pendingRequest(Instant start, Instant end, Instant createdAt) {
        // bookings must start in the future, so a started request is created for a later slot and moved afterwards
        ReservationView view = reservations.book(driver.getId(),
                new BookCommand(spot.getId(), vehicle.getId(), start.isBefore(NOW) ? T10 : start, start.isBefore(NOW) ? T12 : end,
                        "pm_card_visa")).reservation();
        em.flush();
        jdbc.update("update reservations set created_at = ? where id = ?", java.sql.Timestamp.from(createdAt), view.id());
        if (start.isBefore(NOW)) {
            jdbc.update("update reservations set starts_at = ?, ends_at = ? where id = ?",
                    java.sql.Timestamp.from(start), java.sql.Timestamp.from(end), view.id());
        }
        em.clear();
        return view.id();
    }

    private List<String> types(User who) {
        return notifications.list(who.getId(), false, 0, 20).stream().map(NotificationView::type).toList();
    }
}
