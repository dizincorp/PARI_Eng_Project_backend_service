package pl.pollub.pari.devdata;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The production guard of the scenario seed (PARI-94). */
class SeedGuardTest {

    private static final String LOCAL_DB = "jdbc:postgresql://localhost:5432/pari";

    @Test
    void runsOnALocalProfileAgainstALocalDatabase() {
        assertThatCode(() -> SeedGuard.check(List.of("local", "scenario-seed"), LOCAL_DB)).doesNotThrowAnyException();
        assertThatCode(() -> SeedGuard.check(List.of("test"), "jdbc:postgresql://127.0.0.1:5433/pari")).doesNotThrowAnyException();
        assertThatCode(() -> SeedGuard.check(List.of("local"), "jdbc:postgresql://postgres-seeded:5432/pari")).doesNotThrowAnyException();
        assertThatCode(() -> SeedGuard.check(List.of("local"), "jdbc:postgresql://[::1]:5432/pari")).doesNotThrowAnyException();
    }

    @Test
    void refusesAProductionProfileEvenNextToLocal() {
        for (String production : List.of("prod", "production", "Production", "staging")) {
            assertThatThrownBy(() -> SeedGuard.check(List.of("local", production), LOCAL_DB))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("production-like profile");
        }
    }

    @Test
    void refusesWhenNeitherLocalNorTestIsActive() {
        assertThatThrownBy(() -> SeedGuard.check(List.of(), LOCAL_DB)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'local' or 'test' profile");
        assertThatThrownBy(() -> SeedGuard.check(List.of("scenario-seed"), LOCAL_DB)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesADatabaseThatIsNotOnThisMachine() {
        for (String url : List.of("jdbc:postgresql://db.example.com:5432/pari", "jdbc:postgresql://10.0.0.5/pari",
                "jdbc:postgresql://localhost.evil.example/pari", "jdbc:postgresql:///pari", "not a url", "", "jdbc:postgresql://")) {
            assertThatThrownBy(() -> SeedGuard.check(List.of("local"), url)).as(url).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not local");
        }
        assertThatThrownBy(() -> SeedGuard.check(List.of("local"), (String) null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readsTheProfilesOfAnEnvironment() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local", "prod");

        assertThatThrownBy(() -> SeedGuard.check(env, LOCAL_DB)).isInstanceOf(IllegalStateException.class);
        MockEnvironment ok = new MockEnvironment();
        ok.setActiveProfiles("local");
        assertThatCode(() -> SeedGuard.check(ok, LOCAL_DB)).doesNotThrowAnyException();
    }

    @Test
    void readsTheHostOfAJdbcUrl() {
        assertThat(SeedGuard.hostOf(LOCAL_DB)).isEqualTo("localhost");
        assertThat(SeedGuard.hostOf("postgresql://localhost/pari")).isNull();
    }
}
