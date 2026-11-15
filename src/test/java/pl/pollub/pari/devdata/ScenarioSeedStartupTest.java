package pl.pollub.pari.devdata;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import pl.pollub.pari.reservation.repository.ReservationRepository;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.support.PostgresTestConfig;
import pl.pollub.pari.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real start-up path of the seed (PARI-94): the whole application starts with the {@code scenario-seed} profile,
 * the guard accepts the local test database, and the runner fills it. Needs Docker.
 */
@SpringBootTest
@Import(PostgresTestConfig.class)
@ActiveProfiles({"test", "scenario-seed"})
@TestPropertySource(properties = "PARI_SEED_PASSWORD=Startup-pass-1")
class ScenarioSeedStartupTest {

    @Autowired
    ApplicationContext context;
    @Autowired
    UserRepository users;
    @Autowired
    ReservationRepository reservations;

    @Test
    void theProfileSeedsTheDatabaseAtStartUpWithTheConfiguredPassword() {
        assertThat(context.getBeansOfType(ScenarioSeeder.class)).hasSize(1);
        assertThat(users.findByEmail("support@seed.pari.test")).isPresent();
        assertThat(users.findByEmail("jan.kowalski@seed.pari.test")).isPresent();
        assertThat(reservations.findByCode("SEED-OVERDUE-AUTO")).get()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(ReservationStatus.COMPLETED));
        assertThat(reservations.findByCode("SEED-OVERDUE-TICKET")).get()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(ReservationStatus.IN_PROGRESS));
    }
}
