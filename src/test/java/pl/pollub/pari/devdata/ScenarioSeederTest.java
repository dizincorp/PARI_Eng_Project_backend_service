package pl.pollub.pari.devdata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import pl.pollub.pari.availability.service.AvailabilityService;
import pl.pollub.pari.common.domain.TimeRange;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.payment.domain.PaymentStatus;
import pl.pollub.pari.payment.domain.PaymentType;
import pl.pollub.pari.payment.repository.PaymentRepository;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.repository.ReservationRepository;
import pl.pollub.pari.reservation.service.OverdueSessionService;
import pl.pollub.pari.review.repository.ReviewRepository;
import pl.pollub.pari.review.service.ReviewService;
import pl.pollub.pari.spot.domain.Occupancy;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.spot.repository.ParkingSpotRepository;
import pl.pollub.pari.support.ServiceTestBase;
import pl.pollub.pari.support.domain.Report;
import pl.pollub.pari.support.domain.ReportStatus;
import pl.pollub.pari.support.domain.TicketSource;
import pl.pollub.pari.support.domain.TicketType;
import pl.pollub.pari.support.repository.ReportRepository;
import pl.pollub.pari.support.service.SupportTicketService;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.repository.UserRepository;
import pl.pollub.pari.user.repository.VehicleRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The scenario seed (PARI-94) on the real schema, with the real services and a fixed clock. */
@Import({ScenarioSeeder.class, SupportTicketService.class, ReviewService.class, OverdueSessionService.class,
        ScenarioSeederTest.Encoder.class})
class ScenarioSeederTest extends ServiceTestBase {

    @TestConfiguration
    static class Encoder {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }
    }

    private static final ScenarioSeeder.Credentials CREDENTIALS = new ScenarioSeeder.Credentials("Seed-pass-1", "Admin-pass-1", "Support-pass-1");

    @Autowired
    ScenarioSeeder seeder;
    @Autowired
    UserRepository users;
    @Autowired
    VehicleRepository vehicles;
    @Autowired
    ParkingSpotRepository spots;
    @Autowired
    ReservationRepository reservations;
    @Autowired
    PaymentRepository payments;
    @Autowired
    ReportRepository reports;
    @Autowired
    ReviewRepository reviews;
    @Autowired
    AvailabilityService availability;
    @Autowired
    PasswordEncoder encoder;

    ScenarioSeeder.Result first;

    @BeforeEach
    void seed() {
        first = seeder.seed(CREDENTIALS);
        em.flush();
        em.clear();
    }

    private Reservation reservation(String suffix) {
        return reservations.findByCode("SEED-" + suffix).orElseThrow();
    }

    private ParkingSpot spot(String namePrefix) {
        return spots.findAll().stream().filter(s -> s.getName().startsWith(namePrefix)).findFirst().orElseThrow();
    }

    private List<Report> ticketsOf(String suffix) {
        return reports.findAll().stream().filter(r -> r.getReservation().getCode().equals("SEED-" + suffix)).toList();
    }

    // ---- accounts and listings

    @Test
    void createsDriversOwnersDualRoleAccountsAndStaffThatCanLogIn() {
        assertThat(first.users()).isEqualTo(8);
        User ola = users.findByEmail("ola.lewandowska@seed.pari.test").orElseThrow();
        User marek = users.findByEmail("marek.kaminski@seed.pari.test").orElseThrow();
        assertThat(ola.hasRole(Role.DRIVER) && ola.hasRole(Role.OWNER)).isTrue();
        assertThat(marek.hasRole(Role.DRIVER) && marek.hasRole(Role.OWNER)).isTrue();
        User admin = users.findByEmail("admin@seed.pari.test").orElseThrow();
        User support = users.findByEmail("support@seed.pari.test").orElseThrow();
        assertThat(admin.hasRole(Role.ADMIN)).isTrue();
        assertThat(support.hasRole(Role.SUPPORT)).isTrue();
        assertThat(encoder.matches("Admin-pass-1", admin.getPasswordHash())).isTrue();
        assertThat(encoder.matches("Support-pass-1", support.getPasswordHash())).isTrue();
        assertThat(encoder.matches("Seed-pass-1", users.findByEmail("jan.kowalski@seed.pari.test").orElseThrow().getPasswordHash())).isTrue();
        assertThat(vehicles.findAll()).extracting(v -> v.getPlateNumber()).doesNotHaveDuplicates().hasSize(6);
    }

    @Test
    void createsLublinListingsWithOneListingOfSeveralIndependentSpaces() {
        assertThat(first.spots()).isEqualTo(6);
        assertThat(spots.findAll()).allSatisfy(s -> {
            assertThat(s.getAddress().city()).isEqualTo("Lublin");
            assertThat(s.getLocation().lat()).isBetween(51.2, 51.3);
            assertThat(s.getLocation().lng()).isBetween(22.4, 22.7);
        });
        assertThat(spot("Plac Litewski").liveUnits()).hasSize(3);
        assertThat(spots.findAll()).extracting(s -> s.getHourlyPrice().amountMinor()).doesNotHaveDuplicates();
    }

    // ---- reservations and their lifecycle

    @Test
    void everyLifecycleStateIsPresent() {
        assertThat(reservation("UPCOMING").getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation("APPROACHING").getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation("PENDING").getStatus()).isEqualTo(ReservationStatus.PENDING_APPROVAL);
        assertThat(reservation("EXPIRED").getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservation("CANCELLED").getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation("NO-SHOW").getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
        assertThat(reservation("ACTIVE").getStatus()).isEqualTo(ReservationStatus.IN_PROGRESS);
        assertThat(reservation("ACTIVE").getCheckInAt()).isNotNull();
        Reservation done = reservation("DONE-REVIEWED");
        assertThat(done.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(done.getCheckInAt()).isNotNull();
        assertThat(done.getCheckOutAt()).isAfter(done.getCheckInAt());
        assertThat(reviews.existsByReservationIdAndAuthorId(done.getId(), done.getDriver().getId())).isTrue();
    }

    @Test
    void theTwoSpacesOfOneListingAreBookedForTheSameTime() {
        Reservation one = reservation("SPACE-1");
        Reservation two = reservation("SPACE-2");

        assertThat(one.getSpot().getId()).isEqualTo(two.getSpot().getId());
        assertThat(one.getPeriod()).isEqualTo(two.getPeriod());
        assertThat(one.getUnit().getId()).isNotEqualTo(two.getUnit().getId());
    }

    @Test
    void theOverdueSessionIsEndedByTheRealAutomaticCheckOutAndThePenaltyIsPaid() {
        Reservation r = reservation("OVERDUE-AUTO");

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(r.isAutoCheckedOut()).isTrue();
        assertThat(r.getCheckOutAt()).isEqualTo(NOW);
        assertThat(r.getOverstayPenalty().isZero()).isFalse();
        assertThat(r.getFinalCost()).isEqualTo(r.getSpotCost().add(r.getOverstayPenalty()));
        assertThat(payments.findByReservationIdOrderByCreatedAtAsc(r.getId()))
                .anySatisfy(p -> {
                    assertThat(p.getType()).isEqualTo(PaymentType.PENALTY);
                    assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
                });
        assertThat(r.getSpot().getOccupancy()).isEqualTo(Occupancy.FREE);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_id = ? and action = 'AUTO_CHECKED_OUT'",
                Integer.class, r.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notifications where payload ->> 'reservationId' = ?",
                Integer.class, r.getId().toString())).isPositive();
    }

    @Test
    void aDeclinedCardLeavesTheOverdueReservationWithAFailedPenaltyPayment() {
        Reservation r = reservation("OVERDUE-FAILED");

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(r.isAutoCheckedOut()).isTrue();
        assertThat(payments.findByReservationIdOrderByCreatedAtAsc(r.getId()))
                .anySatisfy(p -> {
                    assertThat(p.getType()).isEqualTo(PaymentType.PENALTY);
                    assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
                });
    }

    // ---- support

    @Test
    void theTicketsMatchTheScenariosAndRespectTheRules() {
        assertThat(ticketsOf("ACTIVE")).singleElement().satisfies(t -> {
            assertThat(t.getType()).isEqualTo(TicketType.CANNOT_LEAVE);
            assertThat(t.getReporter().getEmail()).isEqualTo("jan.kowalski@seed.pari.test");
        });
        assertThat(ticketsOf("DONE-VNL")).singleElement().satisfies(t -> {
            assertThat(t.getType()).isEqualTo(TicketType.VEHICLE_NOT_LEFT);
            assertThat(t.getSource()).isEqualTo(TicketSource.OWNER);
            assertThat(t.getReservation().getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        });
        assertThat(ticketsOf("OVERDUE-TICKET")).singleElement().satisfies(t -> {
            assertThat(t.getType()).isEqualTo(TicketType.VEHICLE_NOT_LEFT);
            assertThat(t.getSource()).isEqualTo(TicketSource.SYSTEM);
            assertThat(t.getStatus()).isEqualTo(ReportStatus.OPEN);
            assertThat(t.getAssignedTo()).isNull();
            assertThat(t.getReservation().getStatus()).isEqualTo(ReservationStatus.IN_PROGRESS);
        });
        assertThat(reports.findAll()).hasSize(4);
    }

    // ---- availability

    @Test
    void blocksAndFreeIntervalsAreSeededAndABlockOverAReservationIsRefused() {
        SeedTimeline t = SeedTimeline.of(NOW, ZoneId.of("Europe/Warsaw"));
        ParkingSpot rynek = spot("Rynek");
        ParkingSpot litewski = spot("Plac Litewski");
        List<String> reasons = jdbc.queryForList("select reason from availability_exceptions where kind = 'BLOCKED'", String.class);
        assertThat(reasons).hasSize(7).allMatch(r -> r.startsWith("Seed:"));
        assertThat(availability.isAvailable(rynek, t.freeInterval())).isTrue();
        assertThat(availability.isAvailable(rynek, t.wholeDayBlock())).isFalse();
        assertThat(availability.isAvailable(rynek, t.partialBlock())).isFalse();
        assertThat(availability.isUnitBlocked(litewski.getId(), litewski.liveUnits().get(2).getId(), t.unitBlock())).isTrue();
        assertThat(availability.isUnitBlocked(litewski.getId(), litewski.liveUnits().get(0).getId(), t.unitBlock())).isFalse();
        // the interval of the upcoming reservation of the listing cannot be blocked
        Reservation upcoming = reservation("UPCOMING");
        assertThatThrownBy(() -> availability.blockPeriod(rynek.getOwner().getId(), rynek.getId(), upcoming.getPeriod(), "Try", null))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("PERIOD_HAS_RESERVATIONS"));
    }

    // ---- idempotency and time

    @Test
    void runningTheSeedAgainCreatesNothingNew() {
        long usersBefore = users.count();
        long vehiclesBefore = vehicles.count();
        long spotsBefore = spots.count();
        long reservationsBefore = reservations.count();
        long ticketsBefore = reports.count();
        long paymentsBefore = payments.count();
        long blocksBefore = jdbc.queryForObject("select count(*) from availability_exceptions", Long.class);

        ScenarioSeeder.Result second = seeder.seed(CREDENTIALS);
        em.flush();

        assertThat(second).isEqualTo(new ScenarioSeeder.Result(0, 0, 0));
        assertThat(users.count()).isEqualTo(usersBefore);
        assertThat(vehicles.count()).isEqualTo(vehiclesBefore);
        assertThat(spots.count()).isEqualTo(spotsBefore);
        assertThat(reservations.count()).isEqualTo(reservationsBefore);
        assertThat(reports.count()).isEqualTo(ticketsBefore);
        assertThat(payments.count()).isEqualTo(paymentsBefore);
        assertThat(jdbc.queryForObject("select count(*) from availability_exceptions", Long.class)).isEqualTo(blocksBefore);
    }

    @Test
    void theReservationsAreCalculatedFromTheClock() {
        SeedTimeline t = SeedTimeline.of(NOW, ZoneId.of("Europe/Warsaw"));

        assertThat(reservation("UPCOMING").getPeriod()).isEqualTo(t.upcoming());
        assertThat(reservation("ACTIVE").getPeriod()).isEqualTo(t.active());
        assertThat(reservation("OVERDUE-AUTO").getPeriod()).isEqualTo(t.overdueAuto());
        assertThat(reservation("APPROACHING").getPeriod().start()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(reservation("ACTIVE").getPeriod().end()).isEqualTo(NOW.plus(Duration.ofMinutes(80)));
        assertThat(reservation("OVERDUE-AUTO").getPeriod().end()).isEqualTo(NOW.minus(Duration.ofMinutes(180)));
    }

    @Test
    void theTimelineMovesWithTheDateAndKeepsItsShape() {
        ZoneId warsaw = ZoneId.of("Europe/Warsaw");
        Instant winter = Instant.parse("2031-01-15T09:30:45Z");
        Instant summer = Instant.parse("2031-07-15T09:30:45Z");

        for (Instant now : List.of(winter, summer)) {
            SeedTimeline t = SeedTimeline.of(now, warsaw);
            Instant minute = Instant.parse(now.toString().substring(0, 17) + "00Z");
            assertThat(t.now()).isEqualTo(minute);
            assertThat(t.approaching().start()).isEqualTo(minute.plus(Duration.ofMinutes(30)));
            assertThat(t.active().contains(minute)).isTrue();
            assertThat(t.overdueAuto().end()).isBefore(minute.minus(Duration.ofMinutes(60)));
            assertThat(t.overdueTicket().end()).isBetween(minute.minus(Duration.ofMinutes(60)), minute.minus(Duration.ofMinutes(15)));
            assertThat(t.upcoming().start()).isAfter(minute);
            assertThat(t.upcoming().start().atZone(warsaw).getHour()).isEqualTo(9);
            assertThat(t.upcoming().start().atZone(warsaw).toLocalDate()).isEqualTo(minute.atZone(warsaw).toLocalDate().plusDays(1));
            assertThat(t.doneReviewed().end()).isBefore(minute);
            assertThat(t.wholeDayBlock().duration()).isEqualTo(Duration.ofHours(24));
            assertThat(t.freeInterval().overlaps(t.upcoming())).isFalse();
            assertThat(t.freeInterval().overlaps(t.partialBlock())).isFalse();
        }
        assertThat(SeedTimeline.of(summer, warsaw).upcoming().start()).isNotEqualTo(SeedTimeline.of(winter, warsaw).upcoming().start());
    }

    @Test
    void aSeededWholeDayBlockStaysAWholeLocalDayAcrossTheClockChange() {
        ZoneId warsaw = ZoneId.of("Europe/Warsaw");
        // the day after this one is 23 hours long in Poland (summer time starts)
        SeedTimeline t = SeedTimeline.of(Instant.parse("2031-03-27T10:00:00Z"), warsaw);

        TimeRange block = t.wholeDayBlock();
        assertThat(block.start().atZone(warsaw).toLocalTime().getHour()).isZero();
        assertThat(block.end().atZone(warsaw).toLocalTime().getHour()).isZero();
        assertThat(block.end().atZone(warsaw).toLocalDate()).isEqualTo(block.start().atZone(warsaw).toLocalDate().plusDays(1));
    }
}
