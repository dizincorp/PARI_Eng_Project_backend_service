package pl.pollub.pari.auth.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.auth.security.JwtConfig;
import pl.pollub.pari.auth.security.JwtProperties;
import pl.pollub.pari.common.exception.AuthenticationFailedException;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.ForbiddenException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.repository.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Registration, login and token issuing (NF03, NF06). Passwords are stored only as BCrypt hashes. */
@Service
public class AuthService {

    /** Roles a person may choose when registering; elevated roles are granted by an administrator. */
    private static final Set<Role> SELF_SERVICE_ROLES = EnumSet.of(Role.DRIVER, Role.OWNER);

    /** Hash of a random password, compared against when the e-mail is unknown so both failures take equally long. */
    private final String dummyHash;

    public record UserView(UUID id, String email, String firstName, String lastName, Role role, int loyaltyPoints) {

        static UserView of(User u) {
            return new UserView(u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(), u.getActiveRole(), u.getLoyaltyPoints());
        }
    }

    public record AuthResponse(String token, String tokenType, long expiresIn, UserView user) {
    }

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwt;
    private final Clock clock;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder, JwtProperties jwt, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.jwt = jwt;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public UserView register(String email, String password, String firstName, String lastName, String phoneNumber, Role role) {
        if (!SELF_SERVICE_ROLES.contains(role)) {
            throw new IllegalArgumentException("role must be DRIVER or OWNER");
        }
        if (users.existsByEmail(email.trim())) {
            throw emailTaken();
        }
        User user = new User(email, passwordEncoder.encode(password), firstName.trim(), lastName.trim(),
                phoneNumber == null || phoneNumber.isBlank() ? null : phoneNumber.trim(), Set.of(role));
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw emailTaken();
        }
        return UserView.of(user);
    }

    /** The same answer for an unknown e-mail and a wrong password, so accounts cannot be probed. */
    @Transactional(readOnly = true)
    public AuthResponse login(String email, String password) {
        User user = users.findByEmail(email.trim()).orElse(null);
        boolean matches = passwordEncoder.matches(password, user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !matches) {
            throw new AuthenticationFailedException("Invalid e-mail or password");
        }
        if (user.isBlocked()) {
            throw new ForbiddenException("The account is blocked");
        }
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(jwt.ttl()))
                .claim(JwtConfig.ROLES_CLAIM, List.copyOf(user.getRoles().stream().map(Role::name).sorted().toList()))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new AuthResponse(token, "Bearer", jwt.ttl().toSeconds(), UserView.of(user));
    }

    @Transactional(readOnly = true)
    public UserView me(UUID userId) {
        return users.findById(userId).map(UserView::of).orElseThrow(() -> new NotFoundException("User not found"));
    }

    private static ConflictException emailTaken() {
        return new ConflictException("EMAIL_ALREADY_REGISTERED", "An account with this e-mail already exists");
    }
}
