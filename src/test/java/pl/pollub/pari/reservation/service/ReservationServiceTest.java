package pl.pollub.pari.reservation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pl.pollub.pari.availability.service.AvailabilityService;
import pl.pollub.pari.common.domain.TimeRange;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.ForbiddenException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.payment.domain.Payment;
import pl.pollub.pari.payment.domain.PaymentStatus;
import pl.pollub.pari.payment.domain.PaymentType;
import pl.pollub.pari.payment.domain.Payout;
import pl.pollub.pari.payment.domain.PayoutStatus;
import pl.pollub.pari.payment.gateway.MockPaymentGateway;
import pl.pollub.pari.payment.repository.PaymentRepository;
import pl.pollub.pari.payment.repository.PayoutRepository;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.service.ReservationService.BookCommand;
import pl.pollub.pari.reservation.service.ReservationService.BookingResult;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.support.ServiceTestBase;
import pl.pollub.pari.user.domain.PaymentMethod;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** Booking flow on the real schema with the mock payment gateway. Spot price is 10.00 PLN/h, fee 5%, commission 10%. */
class ReservationServiceTest extends ServiceTestBase {

    private static final String VISA = "pm_card_visa";

    @Autowired
    ReservationService service;
    @Autowired
    AvailabilityService availability;
    @Autowired
    PaymentRepository payments;
    @Autowired
    PayoutRepository payouts;

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
        em.flush(); // entities stay managed, so changes made in a test (autoAccept, block) are seen by the services
    }

    @Test
    void bookingAnAutoAcceptSpotConfirmsItAndSchedulesTheOwnerPayout() {
        BookingResult result = book(driver, T10, T12, VISA);

        ReservationView view = result.reservation();
        assertThat(result.paymentFailed()).isFalse();
        assertThat(view.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(view.code()).startsWith("RSV-20291201-");
        assertThat(view.spotCost()).isEqualByComparingTo("20.00");
        assertThat(view.serviceFee()).isEqualByComparingTo("1.00");
        assertThat(view.total()).isEqualByComparingTo("21.00");
        assertThat(view.pinCode()).matches("\\d{4}");
        assertThat(view.qrToken()).isNotBlank();

        List<Payment> charges = payments.findByReservationIdOrderByCreatedAtAsc(view.id());
        assertThat(charges).singleElement().satisfies(p -> {
            assertThat(p.getType()).isEqualTo(PaymentType.CHARGE);
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(p.getAmount().amountMinor()).isEqualTo(2100);
        });
        Payout payout = payouts.findByReservationId(view.id()).orElseThrow();
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.SCHEDULED);
        assertThat(payout.getNet().amountMinor()).isEqualTo(1800);
        assertThat(payout.getReleaseAt()).isEqualTo(T12.plus(Duration.ofHours(24)));
    }

    @Test
    void aDeclinedCardCancelsTheReservationAndFreesTheSlot() {
        BookingResult result = book(driver, T10, T12, MockPaymentGateway.DECLINED_TOKEN);

        assertThat(result.paymentFailed()).isTrue();
        assertThat(result.paymentFailureReason()).isEqualTo("The card was declined");
        assertThat(result.reservation().status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(payments.findByReservationIdOrderByCreatedAtAsc(result.reservation().id()))
                .singleElement().extracting(Payment::getStatus).isEqualTo(PaymentStatus.FAILED);
        assertThat(payouts.findByReservationId(result.reservation().id())).isEmpty();

        // the same slot can be booked again
        assertThat(book(driver, T10, T12, VISA).reservation().status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void anOverlappingBookingIsRefusedButAnAdjacentOneIsAllowed() {
        book(driver, T10, T12, VISA);
        User other = persistUser(Role.DRIVER);
        Vehicle otherVehicle = persistVehicle(other);

        assertThatThrownBy(() -> service.book(other.getId(),
                new BookCommand(spot.getId(), otherVehicle.getId(), T10.plusSeconds(1800), T14, VISA)))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("SPOT_ALREADY_RESERVED"));

        BookingResult adjacent = service.book(other.getId(), new BookCommand(spot.getId(), otherVehicle.getId(), T12, T14, VISA));
        assertThat(adjacent.reservation().status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void onlyActiveDriversCanBookAndNobodyCanBookTheirOwnSpace() {
        assertThatThrownBy(() -> service.book(owner.getId(), new BookCommand(spot.getId(), vehicle.getId(), T10, T12, VISA)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("DRIVER");

        User both = persistUser(Role.DRIVER, Role.OWNER);
        Vehicle bothVehicle = persistVehicle(both);
        ParkingSpot ownSpot = persistSpot(both, 51.25, 22.57, 1000);
        assertThatThrownBy(() -> service.book(both.getId(), new BookCommand(ownSpot.getId(), bothVehicle.getId(), T10, T12, VISA)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("own");

        driver.block();
        assertThatThrownBy(() -> book(driver, T10, T12, VISA)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aReservationMustStartInTheFuture() {
        assertThatThrownBy(() -> book(driver, NOW.minusSeconds(3600), NOW.plusSeconds(3600), VISA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
    }

    @Test
    void aSpaceClosedByTheWeeklyScheduleCannotBeBooked() {
        availability.replaceWeeklyRules(owner.getId(), spot.getId(), List.of(
                new AvailabilityService.RuleCommand(1, LocalTime.of(8, 0), LocalTime.of(18, 0), null, null)));

        Instant tuesdayNight = Instant.parse("2030-01-08T01:00:00Z");
        assertThatThrownBy(() -> book(driver, tuesdayNight, tuesdayNight.plusSeconds(7200), VISA))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("SPOT_NOT_AVAILABLE"));
        assertThat(book(driver, T10, T12, VISA).reservation().status()).isEqualTo(ReservationStatus.CONFIRMED); // Monday 11-13 local
    }

    @Test
    void anOwnerCannotBlockAPeriodThatAlreadyHasAReservation() {
        book(driver, T10, T12, VISA);

        assertThatThrownBy(() -> availability.blockPeriod(owner.getId(), spot.getId(),
                new TimeRange(T10, T14), "repair"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("PERIOD_HAS_RESERVATIONS"));
    }

    @Test
    void whenAutoAcceptIsOffThePaidRequestWaitsForTheOwnerWhoCanAccept() {
        spot.setAutoAccept(false);

        ReservationView pending = book(driver, T10, T12, VISA).reservation();
        assertThat(pending.status()).isEqualTo(ReservationStatus.PENDING_APPROVAL);
        assertThat(pending.pinCode()).isNull();

        ReservationView accepted = service.accept(owner.getId(), pending.id());
        assertThat(accepted.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(accepted.pinCode()).isNull(); // the owner never sees the PIN
        assertThat(service.get(driver.getId(), pending.id()).pinCode()).matches("\\d{4}");
    }

    @Test
    void rejectingARequestRefundsTheDriverAndCancelsThePayout() {
        spot.setAutoAccept(false);
        ReservationView pending = book(driver, T10, T12, VISA).reservation();

        ReservationView rejected = service.reject(owner.getId(), pending.id(), "Not available that day");

        assertThat(rejected.status()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(payments.findByReservationIdOrderByCreatedAtAsc(pending.id()))
                .extracting(Payment::getType, Payment::getStatus)
                .containsExactly(tuple(PaymentType.CHARGE, PaymentStatus.SUCCEEDED),
                        tuple(PaymentType.REFUND, PaymentStatus.SUCCEEDED));
        assertThat(payouts.findByReservationId(pending.id()).orElseThrow().getStatus()).isEqualTo(PayoutStatus.CANCELLED);
    }

    @Test
    void theDriverCanCancelBeforeCheckInAndGetsTheWholeAmountBack() {
        ReservationView booked = book(driver, T10, T12, VISA).reservation();

        ReservationView cancelled = service.cancel(driver.getId(), booked.id(), "Plans changed");

        assertThat(cancelled.status()).isEqualTo(ReservationStatus.CANCELLED);
        Payment refund = payments.findByReservationIdOrderByCreatedAtAsc(booked.id()).get(1);
        assertThat(refund.getType()).isEqualTo(PaymentType.REFUND);
        assertThat(refund.getAmount().amountMinor()).isEqualTo(2100);
        assertThat(payouts.findByReservationId(booked.id()).orElseThrow().getStatus()).isEqualTo(PayoutStatus.CANCELLED);
    }

    @Test
    void strangersCannotSeeOrCancelAReservation() {
        ReservationView booked = book(driver, T10, T12, VISA).reservation();
        UUID stranger = persistUser(Role.DRIVER).getId();

        assertThatThrownBy(() -> service.get(stranger, booked.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.cancel(stranger, booked.id(), "x")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.accept(driver.getId(), booked.id())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void driversSeeTheirReservationsAndOwnersSeeTheIncomingOnes() {
        ReservationView booked = book(driver, T10, T12, VISA).reservation();

        assertThat(service.listForDriver(driver.getId(), 0, 20)).extracting(ReservationView::id).containsExactly(booked.id());
        assertThat(service.listForOwner(owner.getId(), null, null, 0, 20)).extracting(ReservationView::id).containsExactly(booked.id());
        assertThat(service.listForOwner(owner.getId(), null, ReservationStatus.CANCELLED, 0, 20)).isEmpty();
        assertThat(service.listForDriver(owner.getId(), 0, 20)).isEmpty();
    }

    @Test
    void theQuoteMatchesWhatBookingCharges() {
        PricingService.Quote quote = service.quote(spot.getId(), T10, T12);

        assertThat(quote.total().amountMinor()).isEqualTo(2100);
        assertThat(book(driver, T10, T12, VISA).reservation().total()).isEqualByComparingTo(quote.total().toDecimal());
    }

    @Test
    void checkInNeedsTheIssuedPinOrQrTokenAndARunningPeriod() {
        ReservationView running = confirmed(NOW.minusSeconds(3600), NOW.plusSeconds(3600));

        assertThatThrownBy(() -> service.checkIn(driver.getId(), running.id(), "0000x", null)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.checkIn(driver.getId(), running.id(), null, null)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.checkIn(owner.getId(), running.id(), running.pinCode(), null)).isInstanceOf(NotFoundException.class);

        ReservationView checkedIn = service.checkIn(driver.getId(), running.id(), running.pinCode(), null);
        assertThat(checkedIn.status()).isEqualTo(ReservationStatus.IN_PROGRESS);
        assertThat(checkedIn.checkInAt()).isEqualTo(NOW);
        assertThat(service.activeSession(driver.getId()).id()).isEqualTo(running.id());
    }

    @Test
    void checkInWorksWithTheQrTokenAndNotBeforeTheStart() {
        ReservationView future = book(driver, T10, T12, VISA).reservation();
        assertThatThrownBy(() -> service.checkIn(driver.getId(), future.id(), future.pinCode(), null))
                .hasMessageContaining("check-in is possible only between");

        ReservationView running = confirmed(NOW.minusSeconds(60), NOW.plusSeconds(3600));
        assertThat(service.checkIn(driver.getId(), running.id(), null, running.qrToken()).status())
                .isEqualTo(ReservationStatus.IN_PROGRESS);
    }

    @Test
    void checkingOutOnTimeCostsOnlyTheBookedSpotCost() {
        ReservationView running = confirmed(NOW.minusSeconds(3600), NOW.plusSeconds(3600));
        service.checkIn(driver.getId(), running.id(), running.pinCode(), null);

        ReservationView done = service.checkOut(driver.getId(), running.id());

        assertThat(done.status()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(done.checkOutAt()).isEqualTo(NOW);
        assertThat(done.overstayPenalty()).isNull();
        assertThat(done.finalCost()).isEqualByComparingTo("20.00");
        assertThatThrownBy(() -> service.activeSession(driver.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void overstayingIsChargedAPenaltyToTheDefaultPaymentMethod() {
        PaymentMethod card = new PaymentMethod(driver, "pm_card_visa", "visa", "4242", 12, 2030);
        card.markDefault(true);
        em.persist(card);
        // reserved until 90 minutes ago; rate 10.00/h x 1.50 x 1.5 h = 22.50
        Reservation overdue = persistReservation(spot, driver, vehicle, NOW.minusSeconds(3 * 3600), NOW.minusSeconds(90 * 60));
        overdue.markPaid(true);
        overdue.checkIn(NOW.minusSeconds(2 * 3600));
        em.flush();

        ReservationView done = service.checkOut(driver.getId(), overdue.getId());

        assertThat(done.overstayPenalty()).isEqualByComparingTo("22.50");
        assertThat(done.finalCost()).isEqualByComparingTo(overdue.getSpotCost().add(Money.pln(2250)).toDecimal());
        assertThat(payments.findByReservationIdOrderByCreatedAtAsc(overdue.getId()))
                .extracting(Payment::getType, Payment::getStatus)
                .containsExactly(tuple(PaymentType.PENALTY, PaymentStatus.SUCCEEDED));
    }

    @Test
    void aPenaltyThatCannotBeChargedIsRecordedAsFailedAndCheckOutStillCompletes() {
        Reservation overdue = persistReservation(spot, driver, vehicle, NOW.minusSeconds(3 * 3600), NOW.minusSeconds(3600));
        overdue.markPaid(true);
        overdue.checkIn(NOW.minusSeconds(2 * 3600));
        em.flush();

        ReservationView done = service.checkOut(driver.getId(), overdue.getId());

        assertThat(done.status()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(payments.findByReservationIdOrderByCreatedAtAsc(overdue.getId()))
                .extracting(Payment::getType, Payment::getStatus)
                .containsExactly(tuple(PaymentType.PENALTY, PaymentStatus.FAILED));
    }

    /** A paid, confirmed reservation for the given period (booking itself only accepts future starts). */
    private ReservationView confirmed(Instant start, Instant end) {
        Reservation reservation = persistReservation(spot, driver, vehicle, start, end);
        reservation.markPaid(true);
        em.flush();
        return ReservationView.of(reservation, driver.getId());
    }

    private BookingResult book(User who, Instant start, Instant end, String token) {
        return service.book(who.getId(), new BookCommand(spot.getId(), vehicleOf(who).getId(), start, end, token));
    }

    private Vehicle vehicleOf(User who) {
        return who.equals(driver) ? vehicle : persistVehicle(who);
    }
}
