package pl.pollub.pari.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.repository.UserRepository;

import java.util.Set;

/**
 * Creates one ADMIN and one SUPPORT account for local development (PARI-80), so the support workflows can be tried on a
 * clean database without editing it by hand. It is wired only by {@link DevStaffSeederConfig} under the {@code local}
 * profile, so production never runs it. The passwords are not in the code or in the repository: they come from the
 * environment, and an account whose password is missing or shorter than 8 characters is skipped. An existing account is
 * never changed, so running it again creates nothing.
 */
public class DevStaffSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevStaffSeeder.class);

    /** Same minimum as registration; the BCrypt limit of 72 characters is checked as well. */
    private static final int MIN_PASSWORD = 8;
    private static final int MAX_PASSWORD = 72;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public DevStaffSeeder(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    /** Seeds both accounts; returns how many were created. */
    @Transactional
    public int seed(String adminEmail, String adminPassword, String supportEmail, String supportPassword) {
        int created = 0;
        if (seedAccount(adminEmail, adminPassword, "Dev", "Admin", Role.ADMIN)) {
            created++;
        }
        if (seedAccount(supportEmail, supportPassword, "Dev", "Support", Role.SUPPORT)) {
            created++;
        }
        return created;
    }

    private boolean seedAccount(String email, String password, String firstName, String lastName, Role role) {
        if (email == null || email.isBlank()) {
            log.warn("Dev {} account skipped: no e-mail configured", role);
            return false;
        }
        if (password == null || password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD) {
            log.warn("Dev {} account skipped: set its password in the environment ({} to {} characters)", role,
                    MIN_PASSWORD, MAX_PASSWORD);
            return false;
        }
        if (users.existsByEmail(email.trim().toLowerCase())) {
            log.info("Dev {} account already exists, left unchanged", role);
            return false;
        }
        User user = new User(email, passwordEncoder.encode(password), firstName, lastName, null, Set.of(role));
        user.confirmEmail();
        users.save(user);
        log.info("Dev {} account created", role);
        return true;
    }
}
