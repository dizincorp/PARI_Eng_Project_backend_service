package pl.pollub.pari.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import pl.pollub.pari.auth.security.JwtConfig;
import pl.pollub.pari.auth.service.AuthService;
import pl.pollub.pari.common.exception.AuthenticationFailedException;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.ForbiddenException;
import pl.pollub.pari.common.security.PhoneHasher;
import pl.pollub.pari.support.DbTestBase;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.repository.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({AuthService.class, JwtConfig.class, PhoneHasher.class, AuthServiceTest.Time.class})
class AuthServiceTest extends DbTestBase {

    private static final Instant NOW = Instant.parse("2029-12-01T00:00:00Z");

    @Autowired
    AuthService auth;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    JwtDecoder decoder;

    @Test
    void registrationStoresABcryptHashAndTheChosenRole() {
        AuthService.UserView view = auth.register(" Jan@Example.com ", "SecurePassword123!", "Jan", "Kowalski", "+48501234567", Role.DRIVER);

        assertThat(view.email()).isEqualTo("jan@example.com");
        assertThat(view.role()).isEqualTo(Role.DRIVER);
        User stored = users.findById(view.id()).orElseThrow();
        assertThat(stored.getPasswordHash()).startsWith("$2").doesNotContain("SecurePassword123!");
        assertThat(encoder.matches("SecurePassword123!", stored.getPasswordHash())).isTrue();
        assertThat(stored.getRoles()).containsExactly(Role.DRIVER);
    }

    @Test
    void anEmailCanBeRegisteredOnlyOnceIgnoringCase() {
        auth.register("jan@example.com", "SecurePassword123!", "Jan", "K", null, Role.DRIVER);

        assertThatThrownBy(() -> auth.register("JAN@example.com", "SecurePassword123!", "Jan", "K", null, Role.OWNER))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void elevatedRolesCannotBeChosenAtRegistration() {
        assertThatThrownBy(() -> auth.register("admin@example.com", "SecurePassword123!", "A", "B", null, Role.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loginReturnsAVerifiableTokenWithSubjectRolesAndExpiry() {
        AuthService.UserView registered = auth.register("jan@example.com", "SecurePassword123!", "Jan", "Kowalski", null, Role.OWNER);

        AuthService.AuthResponse response = auth.login("JAN@example.com", "SecurePassword123!");

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(86400);
        assertThat(response.user().id()).isEqualTo(registered.id());
        Jwt jwt = decoder.decode(response.token());
        assertThat(jwt.getSubject()).isEqualTo(registered.id().toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("OWNER");
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plusSeconds(86400));
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameFailure() {
        auth.register("jan@example.com", "SecurePassword123!", "Jan", "K", null, Role.DRIVER);

        assertThatThrownBy(() -> auth.login("jan@example.com", "wrong-password")).isInstanceOf(AuthenticationFailedException.class)
                .hasMessage("Invalid e-mail or password");
        assertThatThrownBy(() -> auth.login("nobody@example.com", "whatever123")).isInstanceOf(AuthenticationFailedException.class)
                .hasMessage("Invalid e-mail or password");
    }

    @Test
    void aBlockedAccountCannotLogIn() {
        AuthService.UserView view = auth.register("jan@example.com", "SecurePassword123!", "Jan", "K", null, Role.DRIVER);
        users.findById(view.id()).orElseThrow().block();
        em.flush();

        assertThatThrownBy(() -> auth.login("jan@example.com", "SecurePassword123!")).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aPhoneNumberCanBelongToOneAccountWhateverItsFormatting() {
        auth.register("a@example.com", "SecurePassword123!", "A", "A", "+48 501-234-567", Role.DRIVER);

        assertThatThrownBy(() -> auth.register("b@example.com", "SecurePassword123!", "B", "B", "+48501234567", Role.DRIVER))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("PHONE_ALREADY_REGISTERED"));
        assertThat(auth.register("c@example.com", "SecurePassword123!", "C", "C", null, Role.DRIVER)).isNotNull();
        assertThat(auth.register("d@example.com", "SecurePassword123!", "D", "D", "", Role.DRIVER)).isNotNull();
    }

    @Test
    void profileUpdateChangesNamesPhoneAndAvatar() {
        AuthService.UserView user = auth.register("a@example.com", "SecurePassword123!", "A", "A", null, Role.DRIVER);

        AuthService.UserView updated = auth.updateProfile(user.id(), " Jan ", "Kowalski", "+48501234567", "https://img.example.com/a.jpg");

        assertThat(updated.firstName()).isEqualTo("Jan");
        assertThat(updated.lastName()).isEqualTo("Kowalski");
        assertThat(updated.avatarUrl()).isEqualTo("https://img.example.com/a.jpg");
        User stored = users.findById(user.id()).orElseThrow();
        assertThat(stored.getPhone()).isEqualTo("+48501234567");
        assertThat(stored.isPhoneVerified()).isFalse();
    }

    @Test
    void profileUpdateRefusesAPhoneOfAnotherAccountButAllowsKeepingYourOwn() {
        AuthService.UserView first = auth.register("a@example.com", "SecurePassword123!", "A", "A", "+48501234567", Role.DRIVER);
        AuthService.UserView second = auth.register("b@example.com", "SecurePassword123!", "B", "B", null, Role.DRIVER);

        assertThatThrownBy(() -> auth.updateProfile(second.id(), "B", "B", "+48 501 234 567", null))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getErrorCode()).isEqualTo("PHONE_ALREADY_REGISTERED"));
        assertThat(auth.updateProfile(first.id(), "A2", "A", "+48501234567", null).firstName()).isEqualTo("A2");
    }

    @Test
    void theUserViewCarriesThePhoneNumberOfTheAccount() {
        AuthService.UserView registered = auth.register("a@example.com", "SecurePassword123!", "A", "A", "+48 501 234 567", Role.DRIVER);
        AuthService.UserView withoutPhone = auth.register("b@example.com", "SecurePassword123!", "B", "B", null, Role.DRIVER);

        assertThat(registered.phoneNumber()).isEqualTo("+48 501 234 567");
        assertThat(auth.me(registered.id()).phoneNumber()).isEqualTo("+48 501 234 567");
        assertThat(auth.login("a@example.com", "SecurePassword123!").user().phoneNumber()).isEqualTo("+48 501 234 567");
        assertThat(auth.updateProfile(registered.id(), "A", "A", "+48 600 100 200", null).phoneNumber()).isEqualTo("+48 600 100 200");
        assertThat(withoutPhone.phoneNumber()).isNull();
    }

    @Test
    void phoneAndAvatarLeftOutOfTheRequestStayUnchanged() {
        AuthService.UserView user = auth.register("a@example.com", "SecurePassword123!", "A", "A", "+48501234567", Role.DRIVER);
        auth.updateProfile(user.id(), "A", "A", null, "https://img.example.com/a.jpg");

        AuthService.UserView renamed = auth.updateProfile(user.id(), "Jan", "Kowalski", null, null);

        assertThat(renamed.firstName()).isEqualTo("Jan");
        assertThat(renamed.avatarUrl()).isEqualTo("https://img.example.com/a.jpg");
        assertThat(users.findById(user.id()).orElseThrow().getPhone()).isEqualTo("+48501234567");
    }

    @Test
    void emptyPhoneAndAvatarAreCleared() {
        AuthService.UserView user = auth.register("a@example.com", "SecurePassword123!", "A", "A", "+48501234567", Role.DRIVER);
        auth.updateProfile(user.id(), "A", "A", "+48501234567", "https://img.example.com/a.jpg");

        AuthService.UserView cleared = auth.updateProfile(user.id(), "A", "A", "", "");

        assertThat(cleared.avatarUrl()).isNull();
        assertThat(users.findById(user.id()).orElseThrow().getPhone()).isNull();
        assertThat(auth.register("b@example.com", "SecurePassword123!", "B", "B", "+48501234567", Role.DRIVER)).isNotNull();
    }

    @TestConfiguration
    static class Time {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
