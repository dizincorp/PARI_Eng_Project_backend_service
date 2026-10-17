package pl.pollub.pari.support;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.domain.TimeRange;
import pl.pollub.pari.common.security.EncryptedStringConverter;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.spot.domain.Address;
import pl.pollub.pari.spot.domain.Dimensions;
import pl.pollub.pari.spot.domain.GeoPoint;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base for repository tests: the real Flyway migrations run against PostgreSQL + PostGIS in a container, and Hibernate
 * validates the entities against that schema. Every test runs in a transaction that is rolled back.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfig.class, EncryptedStringConverter.class})
public abstract class DbTestBase {

    protected static final Instant T10 = Instant.parse("2030-01-07T10:00:00Z");
    protected static final Instant T12 = Instant.parse("2030-01-07T12:00:00Z");
    protected static final Instant T14 = Instant.parse("2030-01-07T14:00:00Z");

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    protected EntityManager em;

    @Autowired
    protected JdbcTemplate jdbc;

    protected void flushAndClear() {
        em.flush();
        em.clear();
    }

    protected User persistUser(Role... roles) {
        User user = new User("user" + SEQ.incrementAndGet() + "@example.com", "hash", "Test", "User", "+48 600 100 200", Set.of(roles));
        em.persist(user);
        return user;
    }

    protected Vehicle persistVehicle(User user) {
        Vehicle vehicle = new Vehicle(user, "Skoda Octavia", "LU " + SEQ.incrementAndGet());
        em.persist(vehicle);
        return vehicle;
    }

    /** An active spot at the given coordinates. */
    protected ParkingSpot persistSpot(User owner, double lat, double lng, long hourlyPriceMinor) {
        ParkingSpot spot = new ParkingSpot(owner, "Spot " + SEQ.incrementAndGet(), "Test spot",
                new Address("Street 1", "Lublin", "20-001", "PL"), new GeoPoint(lat, lng),
                new Dimensions(500, 250, 200), Money.pln(hourlyPriceMinor));
        spot.declareOwnership(Instant.now());
        spot.activate();
        em.persist(spot);
        return spot;
    }

    protected Reservation persistReservation(ParkingSpot spot, User driver, Vehicle vehicle, Instant start, Instant end) {
        Reservation reservation = newReservation(spot, driver, vehicle, start, end);
        em.persist(reservation);
        return reservation;
    }

    protected Reservation newReservation(ParkingSpot spot, User driver, Vehicle vehicle, Instant start, Instant end) {
        return new Reservation("RSV-T-" + SEQ.incrementAndGet(), spot, driver, vehicle, new TimeRange(start, end),
                Money.pln(1000), Money.pln(2000), Money.pln(100), Money.pln(200));
    }
}
