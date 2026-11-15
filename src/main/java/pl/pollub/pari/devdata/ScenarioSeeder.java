package pl.pollub.pari.devdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pl.pollub.pari.availability.service.AvailabilityService;
import pl.pollub.pari.availability.service.AvailabilityService.RuleCommand;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.domain.TimeRange;
import pl.pollub.pari.common.service.PlatformSettings;
import pl.pollub.pari.payment.service.PaymentService;
import pl.pollub.pari.reservation.domain.BookingType;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.repository.ReservationRepository;
import pl.pollub.pari.reservation.service.OverdueSessionService;
import pl.pollub.pari.reservation.service.PricingService;
import pl.pollub.pari.reservation.service.ReservationService;
import pl.pollub.pari.review.service.ReviewService;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.spot.domain.SpotUnit;
import pl.pollub.pari.spot.repository.ParkingSpotRepository;
import pl.pollub.pari.spot.service.SpotService;
import pl.pollub.pari.spot.service.SpotService.CreateSpotCommand;
import pl.pollub.pari.support.domain.TicketType;
import pl.pollub.pari.support.service.SupportTicketService;
import pl.pollub.pari.user.domain.PaymentMethod;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;
import pl.pollub.pari.user.repository.PaymentMethodRepository;
import pl.pollub.pari.user.repository.UserRepository;
import pl.pollub.pari.user.repository.VehicleRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A repeatable, Lublin-focused data set for trying the reservation, support and map workflows (PARI-94). It exists for
 * local development and tests only: {@link ScenarioSeedConfig} wires it when {@code pari.seed.enabled=true} and
 * {@link SeedGuard} refuses every other place.
 *
 * <p>Everything is created through the real services where the service can do it (listings, weekly rules, blocks,
 * charging, payouts, cancellation, no-show, reviews, tickets). A reservation in the past cannot be booked through the
 * API, so it is built from the domain entity with explicit times and then goes through the same state changes
 * (payment, check-in, check-out) as a real one. The overdue sessions are NOT completed here: they are ended by the real
 * auto-check-out ({@link OverdueSessionService}), so their final amount, overstay penalty, payment outcome, occupancy,
 * notifications and audit entries are what the application produces.
 *
 * <p>Idempotent: accounts are found by e-mail, vehicles by plate, listings by name and owner, reservations by their
 * fixed code. A second run creates only what is missing and never moves an existing record, so on a later day the dates of
 * an existing data set stay where they were; recreate the database to get a fresh one.
 */
public class ScenarioSeeder {

    private static final Logger log = LoggerFactory.getLogger(ScenarioSeeder.class);

    public static final String DOMAIN = "seed.pari.test";

    /** The passwords of the seeded accounts. They are local development values, not secrets. */
    public record Credentials(String password, String adminPassword, String supportPassword) {
    }

    /** What one run created (everything that already existed is not counted). */
    public record Result(int users, int spots, int reservations) {
    }

    private record Cast(User jan, User maria, User anna, User piotr, User ola, User marek, User admin, User support) {
    }

    private final UserRepository users;
    private final VehicleRepository vehicles;
    private final PaymentMethodRepository paymentMethods;
    private final ParkingSpotRepository spots;
    private final ReservationRepository reservations;
    private final SpotService spotService;
    private final AvailabilityService availability;
    private final PricingService pricing;
    private final PaymentService payments;
    private final ReservationService reservationService;
    private final OverdueSessionService overdue;
    private final SupportTicketService tickets;
    private final ReviewService reviews;
    private final PlatformSettings settings;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final ZoneId zone;
    private final TransactionTemplate tx;

    public ScenarioSeeder(UserRepository users, VehicleRepository vehicles, PaymentMethodRepository paymentMethods,
                          ParkingSpotRepository spots, ReservationRepository reservations, SpotService spotService,
                          AvailabilityService availability, PricingService pricing, PaymentService payments,
                          ReservationService reservationService, OverdueSessionService overdue, SupportTicketService tickets,
                          ReviewService reviews, PlatformSettings settings, PasswordEncoder passwordEncoder, Clock clock,
                          ZoneId businessZone, PlatformTransactionManager transactionManager) {
        this.users = users;
        this.vehicles = vehicles;
        this.paymentMethods = paymentMethods;
        this.spots = spots;
        this.reservations = reservations;
        this.spotService = spotService;
        this.availability = availability;
        this.pricing = pricing;
        this.payments = payments;
        this.reservationService = reservationService;
        this.overdue = overdue;
        this.tickets = tickets;
        this.reviews = reviews;
        this.settings = settings;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.zone = businessZone;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Seeds the whole data set in one transaction: either all of it is there afterwards or none of what this run added. */
    public Result seed(Credentials credentials) {
        return tx.execute(status -> {
            newSpots.clear();
            SeedTimeline t = SeedTimeline.of(clock.instant(), zone);
            int[] created = new int[3];
            Cast cast = accounts(credentials, created);
            ParkingSpot rynek = spot(cast.anna, "Rynek - garaż podziemny", "Garaż pod Starym Miastem, wjazd od ul. Grodzkiej.",
                    "ul. Grodzka 12", "20-112", 51.2477, 22.5683, 1, 1200, 8000L, 70000L, true, Set.of("COVERED", "CCTV", "GATE"),
                    "Wjazd szlabanem, miejsce na poziomie -1.", null, created);
            ParkingSpot litewski = spot(cast.anna, "Plac Litewski - trzy miejsca", "Trzy miejsca przy placu, każde można rezerwować osobno.",
                    "ul. Krakowskie Przedmieście 40", "20-002", 51.2490, 22.5545, 3, 800, 6000L, 50000L, true, Set.of("LIGHTING", "CCTV"),
                    "Miejsca oznaczone numerami 1-3 przy bramie.", null, created);
            ParkingSpot plaza = spot(cast.piotr, "Lublin Plaza - podjazd", "Miejsce na prywatnym podjeździe, rezerwacja wymaga zgody właściciela.",
                    "ul. Lipowa 13", "20-020", 51.2478, 22.5710, 1, 600, 4000L, 35000L, false, Set.of("LIGHTING"),
                    "Dzwonek przy furtce, wjazd od podwórka.", null, created);
            ParkingSpot bronowice = spot(cast.ola, "Bronowice - miejsce przy domu", "Miejsce przy domu jednorodzinnym, dostępne w dni robocze.",
                    "ul. Wallenroda 8", "20-607", 51.2670, 22.5120, 1, 400, 3000L, 25000L, true, Set.of("COVERED", "EV_CHARGER"),
                    "Brama na pilota, kod w wiadomości po rezerwacji.", "4455", created);
            ParkingSpot umcs = spot(cast.marek, "UMCS - parking kampusowy", "Parking przy kampusie, bez wiaty.",
                    "ul. Akademicka 5", "20-033", 51.2455, 22.5420, 1, 500, 3500L, 28000L, true, Set.of("LIGHTING", "WIDE_SPACE"),
                    "Wjazd od ul. Akademickiej, szlaban otwiera się automatycznie.", null, created);
            ParkingSpot czechow = spot(cast.piotr, "Czechów - garaż", "Garaż bez rezerwacji, do sprawdzania wyszukiwarki i kalendarza.",
                    "ul. Wileńska 2", "20-845", 51.2755, 22.5400, 1, 700, 5000L, 42000L, true, Set.of("COVERED", "GATE", "DISABLED_ACCESS"),
                    "Garaż numer 2 w rzędzie, brama na pilota.", null, created);

            weekly(rynek, cast.anna, 1, 7, 6, 22);
            weekly(litewski, cast.anna, 1, 7, 6, 22);
            weekly(plaza, cast.piotr, 1, 7, 6, 22);
            weekly(bronowice, cast.ola, 1, 5, 7, 19);
            weekly(umcs, cast.marek, 1, 5, 6, 22);
            weekly(czechow, cast.piotr, 1, 7, 6, 22);
            blocks(rynek, litewski, plaza, bronowice, umcs, t, cast);

            reservations(cast, t, rynek, litewski, plaza, bronowice, umcs, created);
            log.info("Scenario seed finished: {} accounts, {} listings, {} reservations created in this run", created[0], created[1], created[2]);
            return new Result(created[0], created[1], created[2]);
        });
    }

    // ---- accounts

    private Cast accounts(Credentials c, int[] created) {
        User jan = account("jan.kowalski", "Jan", "Kowalski", Set.of(Role.DRIVER), c.password(), created);
        User maria = account("maria.nowak", "Maria", "Nowak", Set.of(Role.DRIVER), c.password(), created);
        User anna = account("anna.wisniewska", "Anna", "Wiśniewska", Set.of(Role.OWNER), c.password(), created);
        User piotr = account("piotr.zielinski", "Piotr", "Zieliński", Set.of(Role.OWNER), c.password(), created);
        User ola = account("ola.lewandowska", "Ola", "Lewandowska", Set.of(Role.DRIVER, Role.OWNER), c.password(), created);
        User marek = account("marek.kaminski", "Marek", "Kamiński", Set.of(Role.DRIVER, Role.OWNER), c.password(), created);
        User admin = account("admin", "Seed", "Admin", Set.of(Role.ADMIN), c.adminPassword(), created);
        User support = account("support", "Seed", "Support", Set.of(Role.SUPPORT), c.supportPassword(), created);
        vehicle(jan, "Skoda Octavia", "LU SD101");
        vehicle(jan, "Toyota Yaris", "LU SD102");
        vehicle(maria, "Renault Clio", "LU SD201");
        vehicle(maria, "Kia Ceed", "LU SD202");
        vehicle(ola, "Fiat 500", "LU SD301");
        vehicle(marek, "Ford Focus", "LU SD401");
        paymentMethod(jan, "pm_seed_jan_visa", "visa", "4242");
        paymentMethod(maria, "pm_seed_maria_mastercard", "mastercard", "4444");
        paymentMethod(ola, "pm_seed_ola_visa", "visa", "4242");
        // Marek's saved (default) card is declined, so a penalty charged to it ends FAILED; he books with another token
        paymentMethod(marek, "pm_card_chargeDeclined", "visa", "0002");
        return new Cast(jan, maria, anna, piotr, ola, marek, admin, support);
    }

    private User account(String local, String first, String last, Set<Role> roles, String password, int[] created) {
        String email = local + "@" + DOMAIN;
        return users.findByEmail(email).orElseGet(() -> {
            if (password == null || password.length() < 8 || password.length() > 72) {
                throw new IllegalStateException("The password for " + email + " must have 8 to 72 characters");
            }
            User user = new User(email, passwordEncoder.encode(password), first, last, null, roles);
            user.confirmEmail();
            created[0]++;
            return users.save(user);
        });
    }

    private Vehicle vehicle(User owner, String model, String plate) {
        String normalized = plate.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return vehicles.findByUserIdAndPlateNumber(owner.getId(), normalized)
                .orElseGet(() -> vehicles.save(new Vehicle(owner, model, plate)));
    }

    private void paymentMethod(User owner, String token, String brand, String last4) {
        if (paymentMethods.findByProviderRef(token).isPresent()) {
            return;
        }
        PaymentMethod method = new PaymentMethod(owner, token, brand, last4, 12, 2099);
        method.markDefault(true);
        paymentMethods.save(method);
    }

    private Vehicle car(User owner, String plate) {
        return vehicle(owner, null, plate);
    }

    // ---- listings

    private ParkingSpot spot(User owner, String name, String description, String street, String postalCode, double lat, double lng,
                             int units, long hourly, long daily, long monthly, boolean autoAccept, Set<String> amenities,
                             String access, String gatePin, int[] created) {
        return spots.findByOwnerIdOrderByCreatedAtDesc(owner.getId()).stream().filter(s -> s.getName().equals(name)).findFirst()
                .orElseGet(() -> {
                    var view = spotService.create(owner.getId(), new CreateSpotCommand(name, description, street, "Lublin", postalCode,
                            "PL", lat, lng, 500, 250, 200, Money.pln(hourly), Money.pln(daily), Money.pln(monthly), access, autoAccept,
                            amenities, List.of(), true, false, gatePin), units);
                    created[1]++;
                    newSpots.add(view.id());
                    return spots.findById(view.id()).orElseThrow();
                });
    }

    /** Ids of the listings this run created: their weekly rules and blocks are set once, with the listing. */
    private final java.util.Set<java.util.UUID> newSpots = new java.util.HashSet<>();

    private void weekly(ParkingSpot spot, User owner, int fromDay, int toDay, int openHour, int closeHour) {
        if (!newSpots.contains(spot.getId())) {
            return;
        }
        List<RuleCommand> rules = new java.util.ArrayList<>();
        for (int day = fromDay; day <= toDay; day++) {
            rules.add(new RuleCommand(day, LocalTime.of(openHour, 0), LocalTime.of(closeHour, 0), null, null));
        }
        if (spot.getName().startsWith("UMCS")) {
            rules.add(new RuleCommand(6, LocalTime.of(8, 0), LocalTime.of(14, 0), null, null));
        }
        availability.replaceWeeklyRules(owner.getId(), spot.getId(), rules);
    }

    /** Whole-day and partial blocks, a block of one space and a closure of a whole listing; none touches a reservation. */
    private void blocks(ParkingSpot rynek, ParkingSpot litewski, ParkingSpot plaza, ParkingSpot bronowice, ParkingSpot umcs,
                        SeedTimeline t, Cast cast) {
        if (newSpots.contains(rynek.getId())) {
            availability.blockPeriod(cast.anna.getId(), rynek.getId(), t.wholeDayBlock(), "Seed: maintenance, whole day", null);
            availability.blockPeriod(cast.anna.getId(), rynek.getId(), t.partialBlock(), "Seed: partial block, two hours", null);
        }
        if (newSpots.contains(litewski.getId())) {
            SpotUnit third = litewski.liveUnits().get(2);
            availability.blockPeriod(cast.anna.getId(), litewski.getId(), t.unitBlock(), "Seed: only space #3 is closed", third.getId());
            availability.blockPeriod(cast.anna.getId(), litewski.getId(), t.closure(), "Seed: the whole listing is closed", null);
        }
        if (newSpots.contains(plaza.getId())) {
            availability.blockPeriod(cast.piotr.getId(), plaza.getId(), t.pendingSpotBlock(), "Seed: afternoon block", null);
        }
        if (newSpots.contains(bronowice.getId())) {
            availability.blockPeriod(cast.ola.getId(), bronowice.getId(), t.weekdaySpotBlock(), "Seed: whole day away", null);
        }
        if (newSpots.contains(umcs.getId())) {
            availability.blockPeriod(cast.marek.getId(), umcs.getId(), t.earlyMorningBlock(), "Seed: early morning block", null);
        }
    }

    // ---- reservations, one scenario each, found again by their fixed code

    private void reservations(Cast cast, SeedTimeline t, ParkingSpot rynek, ParkingSpot litewski, ParkingSpot plaza,
                              ParkingSpot bronowice, ParkingSpot umcs, int[] created) {
        // upcoming and approaching
        Reservation upcoming = reservation("SEED-UPCOMING", rynek, 0, cast.jan, car(cast.jan, "LU SD101"), t.upcoming(), null, created);
        reservation("SEED-APPROACHING", litewski, 0, cast.maria, car(cast.maria, "LU SD201"), t.approaching(), null, created);
        // two different spaces of one listing at the same time
        reservation("SEED-SPACE-1", litewski, 0, cast.jan, car(cast.jan, "LU SD102"), t.sharedSlot(), null, created);
        reservation("SEED-SPACE-2", litewski, 1, cast.maria, car(cast.maria, "LU SD202"), t.sharedSlot(), null, created);
        // waiting for the owner, and one that the owner never answered
        reservation("SEED-PENDING", plaza, 0, cast.maria, car(cast.maria, "LU SD201"), t.pending(), null, created);
        Reservation expired = reservation("SEED-EXPIRED", plaza, 0, cast.jan, car(cast.jan, "LU SD101"), t.expired(), null, created);
        if (expired != null) {
            expired.expire(clock.instant());
            payments.refundInFull(expired);
        }
        // booked and cancelled by the driver (refund through the real cancellation)
        Reservation cancelled = reservation("SEED-CANCELLED", rynek, 0, cast.maria, car(cast.maria, "LU SD202"), t.cancelled(), null, created);
        if (cancelled != null) {
            reservationService.cancel(cast.maria.getId(), cancelled.getId(), "Plans changed");
        }
        // never arrived: marked by the real no-show logic
        Reservation noShow = reservation("SEED-NO-SHOW", umcs, 0, cast.jan, car(cast.jan, "LU SD102"), t.noShow(), null, created);
        if (noShow != null) {
            reservationService.markNoShow(noShow.getId());
        }
        // running now, with a driver ticket "I cannot leave"
        Reservation active = reservation("SEED-ACTIVE", bronowice, 0, cast.jan, car(cast.jan, "LU SD102"), t.active(), "RUNNING", created);
        if (active != null) {
            tickets.create(cast.jan.getId(), active.getId(), TicketType.CANNOT_LEAVE, "The gate does not open, I cannot leave.");
        }
        // ended and checked out, with a review
        Reservation reviewed = reservation("SEED-DONE-REVIEWED", rynek, 0, cast.maria, car(cast.maria, "LU SD201"), t.doneReviewed(), "COMPLETED", created);
        if (reviewed != null) {
            reviews.create(cast.maria.getId(), reviewed.getId(), 5, "Easy to find, clean and well lit.");
        }
        // ended and checked out, but the owner says the car is still there (allowed since PARI-93)
        Reservation doneVnl = reservation("SEED-DONE-VNL", umcs, 0, cast.jan, car(cast.jan, "LU SD101"), t.doneVnl(), "COMPLETED", created);
        if (doneVnl != null) {
            tickets.create(cast.marek.getId(), doneVnl.getId(), TicketType.VEHICLE_NOT_LEFT, "The vehicle is still on my space.");
        }
        // overdue sessions ended by the real automatic check-out: penalty paid, and penalty payment declined
        autoCheckOut(reservation("SEED-OVERDUE-AUTO", litewski, 1, cast.maria, car(cast.maria, "LU SD202"), t.overdueAuto(), "RUNNING", created));
        autoCheckOut(reservation("SEED-OVERDUE-FAILED", rynek, 0, cast.marek, car(cast.marek, "LU SD401"), t.overdueFailed(), "RUNNING", created));
        // overdue but not yet ended: the system ticket is open and waits for support
        Reservation overdueTicket = reservation("SEED-OVERDUE-TICKET", plaza, 0, cast.ola, car(cast.ola, "LU SD301"), t.overdueTicket(), "RUNNING", created);
        if (overdueTicket != null) {
            tickets.createSystemTicket(overdueTicket.getId());
        }
        if (upcoming != null) {
            tickets.create(cast.jan.getId(), upcoming.getId(), TicketType.OTHER, "Is there a charger on level -1?");
        }
    }

    /**
     * Books one reservation like the booking service does, with explicit times, or returns null when it exists already.
     * {@code stage}: null = confirmed (or waiting for the owner), RUNNING = checked in, COMPLETED = checked in and out.
     */
    private Reservation reservation(String code, ParkingSpot spot, int unitIndex, User driver, Vehicle vehicle, TimeRange period,
                                    String stage, int[] created) {
        if (reservations.findByCode(code).isPresent()) {
            return null;
        }
        SpotUnit unit = spot.liveUnits().get(unitIndex);
        PricingService.Quote quote = pricing.quote(spot, period, BookingType.HOURLY, null);
        Reservation r = new Reservation(code, spot, driver, vehicle, period, quote.hourlyRate(), quote.spotCost(), quote.serviceFee(),
                quote.commission(), quote.bookingType(), quote.durationDays(), quote.durationMonths(), quote.baseCost(),
                quote.discountAmount());
        r.applyBuffer(Duration.ofMinutes(settings.integer("reservation_buffer_minutes")));
        r.assignUnit(unit);
        reservations.saveAndFlush(r);
        String token = driver.getEmail().startsWith("marek.") ? "pm_seed_marek_visa" : defaultToken(driver);
        PaymentService.ChargeOutcome charge = payments.charge(r, token);
        if (!charge.succeeded()) {
            throw new IllegalStateException("The seed payment of " + code + " was refused: " + charge.failureReason());
        }
        r.markPaid(spot.isAutoAccept());
        payments.schedulePayout(r);
        created[2]++;
        if (stage != null) {
            if (r.getStatus() == ReservationStatus.PENDING_APPROVAL) {
                r.accept();
            }
            r.checkIn(period.start().plus(Duration.ofMinutes(2)));
            spot.occupy(unit);
            if ("COMPLETED".equals(stage)) {
                r.checkOut(period.end().minus(Duration.ofMinutes(5)));
                spot.release(unit);
            }
        }
        reservations.saveAndFlush(r);
        return r;
    }

    private String defaultToken(User driver) {
        return paymentMethods.findByUserIdOrderByCreatedAtAsc(driver.getId()).stream().findFirst()
                .map(PaymentMethod::getProviderRef).orElseThrow(() -> new IllegalStateException("No payment method for " + driver.getEmail()));
    }

    private void autoCheckOut(Reservation r) {
        if (r != null && !overdue.endSession(r.getId(), overdue.dueBefore())) {
            throw new IllegalStateException("The overdue seed session " + r.getCode() + " was not ended by the automatic check-out");
        }
    }
}
