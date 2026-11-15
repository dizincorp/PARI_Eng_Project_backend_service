package pl.pollub.pari.devdata;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** The runner of the scenario seed asks the guard before it touches anything (PARI-94). */
class ScenarioSeedConfigTest {

    private final ScenarioSeeder seeder = mock(ScenarioSeeder.class);

    private ApplicationRunner runner(String profile, String url) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profile.split(","));
        return new ScenarioSeedConfig().runScenarioSeed(seeder, env, url, "pw-123456", "", "");
    }

    @Test
    void aProductionProfileStopsTheStartUpAndNothingIsSeeded() {
        ApplicationRunner runner = runner("local,prod", "jdbc:postgresql://localhost:5432/pari");

        assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class))).isInstanceOf(IllegalStateException.class);
        verify(seeder, never()).seed(any());
    }

    @Test
    void aRemoteDatabaseStopsTheStartUpAndNothingIsSeeded() {
        ApplicationRunner runner = runner("local", "jdbc:postgresql://db.example.com:5432/pari");

        assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class))).isInstanceOf(IllegalStateException.class);
        verify(seeder, never()).seed(any());
    }

    @Test
    void aLocalDatabaseIsSeededWithTheStaffPasswordsFallingBackToTheGeneralOne() throws Exception {
        runner("local", "jdbc:postgresql://localhost:5432/pari").run(mock(ApplicationArguments.class));

        verify(seeder).seed(new ScenarioSeeder.Credentials("pw-123456", "pw-123456", "pw-123456"));
    }
}
