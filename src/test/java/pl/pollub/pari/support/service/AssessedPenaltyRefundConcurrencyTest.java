package pl.pollub.pari.support.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.domain.TimeRange;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.spot.domain.Address;
import pl.pollub.pari.spot.domain.Dimensions;
import pl.pollub.pari.spot.domain.GeoPoint;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.support.PostgresTestConfig;
import pl.pollub.pari.support.domain.PenaltyState;
import pl.pollub.pari.support.domain.TicketType;
import pl.pollub.pari.user.domain.PaymentMethod;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PARI-101: several support requests to refund the same manual penalty at the same time, each in its own committed
 * transaction (the other tests roll back and cannot race). However many arrive, the driver is refunded once. Needs Docker.
 */
@SpringBootTest
@Import(PostgresTestConfig.class)
@ActiveProfiles("test")
class AssessedPenaltyRefundConcurrencyTest {

    private static final int REQUESTS = 6;

    @Autowired
    SupportCommandService commands;
    @Autowired
    SupportTicketService tickets;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    EntityManager em;
    @Autowired
    JdbcTemplate jdbc;

    record Fixture(UUID supportId, UUID ticketId, UUID reservationId, UUID driverId, UUID penaltyId) {
    }

    @Test
    void theSameOperationSentManyTimesAtOnceRefundsOnceAndEveryCallerSeesTheRefund() throws Exception {
        Fixture f = chargedPenalty();

        List<Object> results = race(f, i -> "same-operation");

        assertThat(results).hasSize(REQUESTS).allSatisfy(r -> assertThat(r).isInstanceOf(TicketView.class));
        assertOnce(f);
    }

    @Test
    void differentOperationsAtOnceRefundOnceAndTheRestAreToldItWasAlreadyRefunded() throws Exception {
        Fixture f = chargedPenalty();

        List<Object> results = race(f, i -> "operation-" + i);

        assertThat(results.stream().filter(r -> r instanceof TicketView)).hasSize(1);
        assertThat(results.stream().filter(r -> r instanceof ConflictException)).hasSize(REQUESTS - 1)
                .allSatisfy(r -> assertThat(((ConflictException) r).getErrorCode()).isEqualTo("PENALTY_ALREADY_REFUNDED"));
        assertOnce(f);
    }

    private void assertOnce(Fixture f) {
        assertThat(count("select count(*) from payments where type = 'REFUND' and reservation_id = ?", f.reservationId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select state from support_penalties where id = ?", String.class, f.penaltyId()))
                .isEqualTo(PenaltyState.REFUNDED.name());
        assertThat(count("select count(*) from audit_log where action = 'SUPPORT_REFUND_PENALTY_ASSESSED' and entity_id = ?", f.ticketId()))
                .isEqualTo(1);
        assertThat(count("select count(*) from notifications where user_id = ? and type = 'PENALTY_REFUNDED'", f.driverId()))
                .isEqualTo(1);
    }

    /** All requests start together; each result is the returned view or the exception that was thrown. */
    private List<Object> race(Fixture f, IntFunction<String> operationId) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < REQUESTS; i++) {
                String op = operationId.apply(i);
                Callable<Object> call = () -> {
                    ready.countDown();
                    go.await();
                    try {
                        return commands.refundAssessedPenalty(f.supportId(), f.ticketId(), f.penaltyId(), "Charged by mistake", op);
                    } catch (RuntimeException e) {
                        return e;
                    }
                };
                futures.add(pool.submit(call));
            }
            ready.await();
            go.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private long count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Long.class, arg);
    }

    /** A committed running reservation with an open ticket and a manual penalty that was charged successfully. */
    private Fixture chargedPenalty() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        record Base(UUID supportId, UUID ticketId, UUID reservationId, UUID driverId) {
        }
        Base base = tx.execute(status -> {
            String suffix = UUID.randomUUID().toString();
            User owner = user("owner-" + suffix, Role.OWNER);
            User driver = user("driver-" + suffix, Role.DRIVER);
            User support = user("support-" + suffix, Role.SUPPORT);
            Vehicle vehicle = new Vehicle(driver, "Skoda Octavia", "CP " + suffix.substring(0, 8));
            em.persist(vehicle);
            ParkingSpot spot = new ParkingSpot(owner, "Concurrency " + suffix, "Test spot", new Address("Street 1", "Lublin", "20-001", "PL"),
                    new GeoPoint(51.2465, 22.5684), new Dimensions(500, 250, 200), Money.pln(1000));
            spot.declareOwnership(Instant.now());
            spot.activate();
            em.persist(spot);
            PaymentMethod card = new PaymentMethod(driver, "pm_card_visa_" + suffix, "visa", "4242", 12, 2030);
            card.markDefault(true);
            em.persist(card);
            Instant end = Instant.now().minus(Duration.ofMinutes(20));
            Instant start = end.minus(Duration.ofHours(2));
            Reservation reservation = new Reservation("RSV-P-" + suffix.substring(0, 8), spot, driver, vehicle, new TimeRange(start, end),
                    Money.pln(1000), Money.pln(2000), Money.pln(100), Money.pln(200));
            em.persist(reservation);
            reservation.markPaid(true);
            reservation.checkIn(start.plusSeconds(60));
            em.flush();
            UUID ticketId = tickets.create(owner.getId(), reservation.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there").id();
            em.flush();
            return new Base(support.getId(), ticketId, reservation.getId(), driver.getId());
        });
        UUID penaltyId = tx.execute(status -> {
            commands.assessPenalty(base.supportId(), base.ticketId(), 2500, null, "Blocked the gate", "assess-1");
            em.flush();
            return tickets.get(base.supportId(), base.ticketId()).penalties().get(0).id();
        });
        tx.executeWithoutResult(status -> commands.chargePenalty(base.supportId(), base.ticketId(), penaltyId, "Collect"));
        return new Fixture(base.supportId(), base.ticketId(), base.reservationId(), base.driverId(), penaltyId);
    }

    private User user(String name, Role role) {
        User user = new User(name + "@example.com", "hash", "Test", "User", "+48 600 100 200", Set.of(role));
        em.persist(user);
        return user;
    }
}
