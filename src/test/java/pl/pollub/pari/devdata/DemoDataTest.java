package pl.pollub.pari.devdata;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.StreamUtils;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.repository.ReservationRepository;
import pl.pollub.pari.spot.repository.ParkingSpotRepository;
import pl.pollub.pari.support.DbTestBase;
import pl.pollub.pari.user.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "seeded" database variant: the migrations plus db/dev. The demo data must be consistent, that is, follow the
 * same money and state rules the services apply, and it must load into the entities.
 */
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/dev")
class DemoDataTest extends DbTestBase {

    @Autowired
    UserRepository users;
    @Autowired
    ParkingSpotRepository spots;
    @Autowired
    ReservationRepository reservations;

    @Test
    void theDemoDataLoadsIntoTheEntitiesWithOneReservationPerState() {
        assertThat(users.findAll()).hasSize(4);
        assertThat(spots.findAll()).hasSize(5);
        assertThat(reservations.findAll()).extracting(r -> r.getStatus())
                .containsExactlyInAnyOrder(ReservationStatus.COMPLETED, ReservationStatus.COMPLETED, ReservationStatus.COMPLETED,
                        ReservationStatus.COMPLETED, ReservationStatus.IN_PROGRESS, ReservationStatus.CONFIRMED,
                        ReservationStatus.PENDING_APPROVAL, ReservationStatus.CANCELLED, ReservationStatus.REJECTED,
                        ReservationStatus.EXPIRED, ReservationStatus.CANCELLED);
    }

    @Test
    void theDemoUsersCanLogInWithTheDocumentedPassword() {
        List<String> hashes = jdbc.queryForList("select password_hash from users", String.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        assertThat(hashes).hasSize(4).allMatch(h -> encoder.matches("Demo1234!", h));
    }

    @Test
    void amountsFollowThePricingRules() {
        // total = spot cost + service fee; the fee and the commission are the configured shares of the spot cost
        assertThat(count("""
                select count(*) from reservations r join parking_spots s on s.id = r.spot_id
                where r.total_minor <> r.spot_cost_minor + r.service_fee_minor
                   or r.service_fee_minor <> round(r.spot_cost_minor * (select setting_value::numeric from platform_settings where setting_key = 'service_fee_rate'))
                   or r.spot_cost_minor <> round(r.hourly_rate_minor::numeric * extract(epoch from (r.ends_at - r.starts_at)) / 3600)
                   or r.commission_minor <> case when s.owner_id = '00000000-0000-0000-0000-000000000003' then 0
                        else round(r.spot_cost_minor * (select setting_value::numeric from platform_settings where setting_key = 'commission_rate')) end
                """)).isZero();
        // the final cost of a completed reservation is the spot cost plus the penalty
        assertThat(count("""
                select count(*) from reservations
                where status = 'COMPLETED' and final_cost_minor <> spot_cost_minor + coalesce(overstay_penalty_minor, 0)
                """)).isZero();
        assertThat(count("select count(*) from reservations where overstay_penalty_minor > 0")).isEqualTo(1);
    }

    @Test
    void paymentsAndPayoutsMatchTheStateOfEachReservation() {
        // exactly one charge each; only the declined one failed
        assertThat(count("select count(*) from reservations r where (select count(*) from payments p where p.reservation_id = r.id and p.type = 'CHARGE') <> 1")).isZero();
        assertThat(count("select count(*) from payments where type = 'CHARGE' and status = 'FAILED'")).isEqualTo(1);
        assertThat(count("""
                select count(*) from payments p join reservations r on r.id = p.reservation_id
                where p.type = 'CHARGE' and p.amount_minor <> r.total_minor
                """)).isZero();
        // a refund returns the whole charge, and only paid reservations that did not take place get one
        assertThat(count("""
                select count(*) from reservations r
                where (r.status in ('REJECTED', 'EXPIRED') or (r.status = 'CANCELLED' and r.cancel_reason not like 'Payment failed%'))
                  <> exists (select 1 from payments p where p.reservation_id = r.id and p.type = 'REFUND' and p.status = 'SUCCEEDED'
                             and p.amount_minor = r.total_minor)
                """)).isZero();
        // payout: spot cost minus commission; none for the declined payment (a row is flagged when having a payout equals being declined); cancelled after a refund; paid once released
        assertThat(count("select count(*) from reservations r where (coalesce(r.cancel_reason, '') like 'Payment failed%') = exists (select 1 from payouts o where o.reservation_id = r.id)")).isZero();
        assertThat(count("""
                select count(*) from payouts o join reservations r on r.id = o.reservation_id
                where o.gross_minor <> r.spot_cost_minor or o.commission_minor <> r.commission_minor
                   or o.release_at <> r.ends_at + interval '24 hours'
                   or o.status <> case when r.status in ('CANCELLED', 'REJECTED', 'EXPIRED') then 'CANCELLED'
                                       when r.status = 'COMPLETED' and o.release_at <= now() then 'PAID' else 'SCHEDULED' end
                   or (o.status = 'PAID') <> (o.paid_at is not null)
                """)).isZero();
    }

    @Test
    void spotsStayConsistentWithTheirReservationsReviewsAndVerification() {
        assertThat(count("""
                select count(*) from parking_spots s
                where (s.is_verified) <> exists (select 1 from verification_requests v where v.spot_id = s.id and v.status = 'APPROVED')
                   or (s.occupancy = 'OCCUPIED') <> exists (select 1 from reservations r where r.spot_id = s.id and r.status = 'IN_PROGRESS')
                   or s.review_count <> (select count(*) from reviews w where w.spot_id = s.id and w.status = 'VISIBLE')
                   or s.avg_rating <> coalesce((select round(avg(w.rating)::numeric, 2) from reviews w where w.spot_id = s.id and w.status = 'VISIBLE'), 0)
                """)).isZero();
        // only completed reservations are reviewed, by their driver, about the owner of the space
        assertThat(count("""
                select count(*) from reviews w join reservations r on r.id = w.reservation_id join parking_spots s on s.id = r.spot_id
                where r.status <> 'COMPLETED' or w.author_id <> r.driver_id or w.reviewee_id <> s.owner_id or w.spot_id <> s.id
                """)).isZero();
        // a completed reservation without a review is left on purpose, to try writing one
        assertThat(count("select count(*) from reservations r where r.status = 'COMPLETED' and not exists (select 1 from reviews w where w.reservation_id = r.id)")).isEqualTo(1);
    }

    @Test
    void theSessionAndTheSlotsAreConsistent() {
        assertThat(count("""
                select count(*) from reservations
                where (status in ('IN_PROGRESS', 'COMPLETED')) <> (check_in_at is not null)
                   or (status = 'COMPLETED') <> (check_out_at is not null)
                   or (status in ('CONFIRMED', 'IN_PROGRESS', 'COMPLETED')) <> (pin_code is not null and qr_token is not null)
                   or (status in ('CANCELLED', 'REJECTED', 'EXPIRED')) <> (cancelled_at is not null)
                   or created_at > now() or cancelled_at < created_at
                """)).isZero();
        // future reservations that hold a slot lie inside the weekly availability of 06:00-22:00 (Europe/Warsaw)
        assertThat(count("""
                select count(*) from reservations
                where status in ('CONFIRMED', 'PENDING_APPROVAL')
                  and ((starts_at at time zone 'Europe/Warsaw')::time < time '06:00'
                       or (ends_at at time zone 'Europe/Warsaw')::time > time '22:00')
                """)).isZero();
        // nothing from the past is left open
        assertThat(count("select count(*) from reservations where status in ('CONFIRMED', 'PENDING_APPROVAL') and starts_at <= now()")).isZero();
    }

    @Test
    void notificationsPointAtExistingReservations() {
        assertThat(count("""
                select count(*) from notifications n
                where not exists (select 1 from reservations r where r.id = (n.payload ->> 'reservationId')::uuid and r.code = n.payload ->> 'code')
                """)).isZero();
        assertThat(count("select count(*) from notifications")).isEqualTo(7);
    }

    @Test
    void runningTheScriptAgainChangesNothing() throws Exception {
        String total = "select (select count(*) from users) + (select count(*) from reservations) + (select count(*) from payments) + (select count(*) from reviews)";
        long before = count(total);
        String script = StreamUtils.copyToString(new ClassPathResource("db/dev/R__demo_data.sql").getInputStream(), StandardCharsets.UTF_8);

        jdbc.execute(script);

        assertThat(count(total)).isEqualTo(before);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
