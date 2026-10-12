package pl.pollub.pari.user.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import pl.pollub.pari.auth.domain.AuthToken;
import pl.pollub.pari.auth.domain.AuthTokenType;
import pl.pollub.pari.auth.repository.AuthTokenRepository;
import pl.pollub.pari.support.DbTestBase;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserRepositoryTest extends DbTestBase {

    @Autowired
    UserRepository users;

    @Autowired
    VehicleRepository vehicles;

    @Autowired
    AuthTokenRepository tokens;

    @Test
    void savesRolesAndEncryptsThePhoneAtRest() {
        User saved = users.save(new User("Anna@Example.com", "hash", "Anna", "Nowak", "+48 600 100 200",
                Set.of(Role.DRIVER, Role.OWNER)));
        flushAndClear();

        User loaded = users.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getEmail()).isEqualTo("anna@example.com");
        assertThat(loaded.getRoles()).containsExactlyInAnyOrder(Role.DRIVER, Role.OWNER);
        assertThat(loaded.getActiveRole()).isEqualTo(Role.DRIVER);
        assertThat(loaded.getPhone()).isEqualTo("+48 600 100 200");
        assertThat(loaded.getCreatedAt()).isNotNull();

        String stored = jdbc.queryForObject("select phone_enc from users where id = ?", String.class, saved.getId());
        assertThat(stored).isNotNull().doesNotContain("600").isNotEqualTo("+48 600 100 200");
    }

    @Test
    void findsByEmailIgnoringCase() {
        users.save(new User("anna@example.com", "hash", "Anna", "Nowak", null, Set.of(Role.DRIVER)));
        flushAndClear();

        assertThat(users.findByEmail("ANNA@Example.COM")).isPresent();
        assertThat(users.existsByEmail("Anna@example.com")).isTrue();
        assertThat(users.findByEmail("other@example.com")).isEmpty();
    }

    @Test
    void emailIsUniqueIgnoringCase() {
        users.saveAndFlush(new User("anna@example.com", "hash", "Anna", "Nowak", null, Set.of(Role.DRIVER)));
        jdbc.update("update users set email = 'Anna@Example.com' where email = 'anna@example.com'");

        assertThatThrownBy(() -> users.saveAndFlush(new User("anna@example.com", "hash", "Anna", "Two", null, Set.of(Role.DRIVER))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void optimisticLockingVersionIncreasesOnUpdate() {
        User user = users.saveAndFlush(new User("anna@example.com", "hash", "Anna", "Nowak", null, Set.of(Role.DRIVER)));
        long first = user.getVersion();

        user.setFirstName("Anne");
        users.saveAndFlush(user);

        assertThat(user.getVersion()).isGreaterThan(first);
    }

    @Test
    void vehiclesAreNormalisedAndScopedToTheirOwner() {
        User owner = persistUser(Role.DRIVER);
        User stranger = persistUser(Role.DRIVER);
        var vehicle = vehicles.save(new pl.pollub.pari.user.domain.Vehicle(owner, "Fiat Panda", "lu 123 ab"));
        flushAndClear();

        assertThat(vehicles.findByUserIdOrderByCreatedAtAsc(owner.getId())).extracting("plateNumber").containsExactly("LU123AB");
        assertThat(vehicles.findByIdAndUserId(vehicle.getId(), owner.getId())).isPresent();
        assertThat(vehicles.findByIdAndUserId(vehicle.getId(), stranger.getId())).isEmpty();
        assertThat(vehicles.findByUserIdAndPlateNumber(owner.getId(), "LU123AB")).isPresent();
    }

    @Test
    void authTokensAreLookedUpByHashAndCanBeInvalidated() {
        User user = persistUser(Role.DRIVER);
        tokens.save(new AuthToken(user, AuthTokenType.REFRESH, "hash-1", T14));
        tokens.save(new AuthToken(user, AuthTokenType.REFRESH, "hash-2", T14));
        flushAndClear();

        assertThat(tokens.findByTypeAndTokenHash(AuthTokenType.REFRESH, "hash-1")).isPresent();
        assertThat(tokens.findByTypeAndTokenHash(AuthTokenType.PASSWORD_RESET, "hash-1")).isEmpty();

        assertThat(tokens.invalidateAll(user.getId(), AuthTokenType.REFRESH, T10)).isEqualTo(2);
        flushAndClear();
        assertThat(tokens.findByTypeAndTokenHash(AuthTokenType.REFRESH, "hash-1").orElseThrow().isUsable(T10)).isFalse();
        assertThat(tokens.deleteExpired(T14.plusSeconds(1))).isEqualTo(2);
    }

    @Test
    void deletingAUserRemovesItsRoleRows() {
        User user = users.saveAndFlush(new User("anna@example.com", "hash", "Anna", "Nowak", null, Set.of(Role.DRIVER, Role.OWNER)));
        UUID id = user.getId();

        users.delete(user);
        flushAndClear();

        assertThat(jdbc.queryForObject("select count(*) from user_roles where user_id = ?", Integer.class, id)).isZero();
    }
}
