package pl.pollub.pari.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import pl.pollub.pari.user.repository.UserRepository;

/**
 * Runs {@link DevStaffSeeder} at start-up, only under the {@code local} profile (PARI-80). Emails are not secret and
 * have defaults; the passwords have none and must be provided as {@code PARI_DEV_ADMIN_PASSWORD} and
 * {@code PARI_DEV_SUPPORT_PASSWORD}.
 */
@Configuration
@Profile("local")
class DevStaffSeederConfig {

    @Bean
    DevStaffSeeder devStaffSeeder(UserRepository users, PasswordEncoder passwordEncoder) {
        return new DevStaffSeeder(users, passwordEncoder);
    }

    @Bean
    ApplicationRunner seedDevStaff(DevStaffSeeder seeder,
                                   @Value("${pari.dev.staff.admin-email:admin@pari.test}") String adminEmail,
                                   @Value("${PARI_DEV_ADMIN_PASSWORD:}") String adminPassword,
                                   @Value("${pari.dev.staff.support-email:support@pari.test}") String supportEmail,
                                   @Value("${PARI_DEV_SUPPORT_PASSWORD:}") String supportPassword) {
        return args -> seeder.seed(adminEmail, adminPassword, supportEmail, supportPassword);
    }
}
