package pl.pollub.pari.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import pl.pollub.pari.auth.security.JwtConfig;
import pl.pollub.pari.common.exception.AuthenticationFailedException;
import pl.pollub.pari.common.security.PhoneHasher;
import pl.pollub.pari.support.DbTestBase;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.repository.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The development ADMIN and SUPPORT accounts (PARI-80). The passwords below are test values. */
@Import({AuthService.class, DevStaffSeeder.class, JwtConfig.class, PhoneHasher.class, DevStaffSeederTest.Time.class})
class DevStaffSeederTest extends DbTestBase {

    private static final String ADMIN = "admin@pari.test";
    private static final String SUPPORT = "support@pari.test";
    private static final String ADMIN_PASSWORD = "test-admin-password";
    private static final String SUPPORT_PASSWORD = "test-support-password";

    @Autowired
    DevStaffSeeder seeder;
    @Autowired
    AuthService auth;
    @Autowired
    UserRepository users;
    @Autowired
    JwtDecoder decoder;

    @Test
    void bothStaffAccountsAreCreatedCanLogInAndGetTheirRole() {
        assertThat(seeder.seed(ADMIN, ADMIN_PASSWORD, SUPPORT, SUPPORT_PASSWORD)).isEqualTo(2);
        em.flush();

        assertThat(decoder.decode(auth.login(ADMIN, ADMIN_PASSWORD).token()).getClaimAsStringList("roles"))
                .containsExactly("ADMIN");
        assertThat(decoder.decode(auth.login(SUPPORT, SUPPORT_PASSWORD).token()).getClaimAsStringList("roles"))
                .containsExactly("SUPPORT");
    }

    @Test
    void runningTheSeederAgainCreatesNothingNew() {
        seeder.seed(ADMIN, ADMIN_PASSWORD, SUPPORT, SUPPORT_PASSWORD);
        em.flush();

        assertThat(seeder.seed(ADMIN, ADMIN_PASSWORD, SUPPORT, SUPPORT_PASSWORD)).isZero();
        em.flush();

        assertThat(users.findAll().stream().filter(u -> u.getEmail().equals(ADMIN) || u.getEmail().equals(SUPPORT))).hasSize(2);
    }

    @Test
    void anAccountWithoutAUsablePasswordIsSkipped() {
        assertThat(seeder.seed(ADMIN, "", SUPPORT, "short")).isZero();
        assertThat(seeder.seed(ADMIN, null, SUPPORT, null)).isZero();
        em.flush();

        assertThat(users.existsByEmail(ADMIN)).isFalse();
        assertThat(users.existsByEmail(SUPPORT)).isFalse();
        assertThatThrownBy(() -> auth.login(ADMIN, "anything")).isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void anExistingAccountWithTheSameEmailIsNotChanged() {
        auth.register(ADMIN, "SecurePassword123!", "Real", "Person", null, Role.DRIVER);
        em.flush();

        assertThat(seeder.seed(ADMIN, ADMIN_PASSWORD, SUPPORT, SUPPORT_PASSWORD)).isEqualTo(1); // only support is new
        em.flush();

        assertThat(users.findByEmail(ADMIN).orElseThrow().hasRole(Role.ADMIN)).isFalse();
        assertThatThrownBy(() -> auth.login(ADMIN, ADMIN_PASSWORD)).isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void theSeederIsWiredOnlyUnderTheLocalProfile() {
        Profile profile = DevStaffSeederConfig.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(Arrays.asList(profile.value())).containsExactly("local");
    }

    @TestConfiguration
    static class Time {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2029-12-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
