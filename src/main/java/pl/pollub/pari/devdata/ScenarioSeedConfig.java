package pl.pollub.pari.devdata;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import pl.pollub.pari.availability.service.AvailabilityService;
import pl.pollub.pari.common.service.PlatformSettings;
import pl.pollub.pari.payment.service.PaymentService;
import pl.pollub.pari.reservation.repository.ReservationRepository;
import pl.pollub.pari.reservation.service.OverdueSessionService;
import pl.pollub.pari.reservation.service.PricingService;
import pl.pollub.pari.reservation.service.ReservationService;
import pl.pollub.pari.review.service.ReviewService;
import pl.pollub.pari.spot.repository.ParkingSpotRepository;
import pl.pollub.pari.spot.service.SpotService;
import pl.pollub.pari.support.service.SupportTicketService;
import pl.pollub.pari.user.repository.PaymentMethodRepository;
import pl.pollub.pari.user.repository.UserRepository;
import pl.pollub.pari.user.repository.VehicleRepository;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Runs the scenario seed at start-up (PARI-94), only when {@code pari.seed.enabled=true} (the {@code scenario-seed}
 * profile sets it). The passwords come from the environment: {@code PARI_SEED_PASSWORD} for the driver and owner accounts,
 * {@code PARI_SEED_ADMIN_PASSWORD} and {@code PARI_SEED_SUPPORT_PASSWORD} for the staff, which fall back to the first one.
 * Without any of them the local development default below is used; it is documented, it is not a secret, and
 * {@link SeedGuard} makes sure it can only end up in a local or test database.
 */
@Configuration
@ConditionalOnProperty(name = "pari.seed.enabled", havingValue = "true")
class ScenarioSeedConfig {

    static final String LOCAL_DEFAULT_PASSWORD = "Seed1234!";

    @Bean
    ScenarioSeeder scenarioSeeder(UserRepository users, VehicleRepository vehicles, PaymentMethodRepository paymentMethods,
                                  ParkingSpotRepository spots, ReservationRepository reservations, SpotService spotService,
                                  AvailabilityService availability, PricingService pricing, PaymentService payments,
                                  ReservationService reservationService, OverdueSessionService overdue,
                                  SupportTicketService tickets, ReviewService reviews, PlatformSettings settings,
                                  PasswordEncoder passwordEncoder, Clock clock, ZoneId businessZone,
                                  PlatformTransactionManager transactionManager) {
        return new ScenarioSeeder(users, vehicles, paymentMethods, spots, reservations, spotService, availability, pricing,
                payments, reservationService, overdue, tickets, reviews, settings, passwordEncoder, clock, businessZone,
                transactionManager);
    }

    @Bean
    ApplicationRunner runScenarioSeed(ScenarioSeeder seeder, Environment env,
                                      @Value("${spring.datasource.url:}") String datasourceUrl,
                                      @Value("${PARI_SEED_PASSWORD:" + LOCAL_DEFAULT_PASSWORD + "}") String password,
                                      @Value("${PARI_SEED_ADMIN_PASSWORD:}") String adminPassword,
                                      @Value("${PARI_SEED_SUPPORT_PASSWORD:}") String supportPassword) {
        return args -> {
            SeedGuard.check(env, datasourceUrl);
            seeder.seed(new ScenarioSeeder.Credentials(password,
                    adminPassword.isBlank() ? password : adminPassword,
                    supportPassword.isBlank() ? password : supportPassword));
        };
    }
}
