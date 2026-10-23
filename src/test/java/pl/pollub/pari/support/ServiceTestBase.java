package pl.pollub.pari.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import pl.pollub.pari.availability.service.AvailabilityService;
import pl.pollub.pari.common.security.EncryptedStringConverter;
import pl.pollub.pari.common.service.AuditTrail;
import pl.pollub.pari.common.service.PlatformSettings;
import pl.pollub.pari.payment.gateway.MockPaymentGateway;
import pl.pollub.pari.payment.service.PaymentService;
import pl.pollub.pari.notification.service.NotificationEventListener;
import pl.pollub.pari.notification.service.NotificationService;
import pl.pollub.pari.reservation.service.PricingService;
import pl.pollub.pari.reservation.service.ReservationExpiryService;
import pl.pollub.pari.reservation.service.ReservationService;
import pl.pollub.pari.spot.service.SpotSearchService;
import pl.pollub.pari.spot.service.SpotService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Base for service tests: the real services and the mock payment gateway run on the real schema. The clock is fixed at
 * {@link #NOW}, before the dates in {@link DbTestBase}, so those dates count as the future.
 */
@Import({PostgresTestConfig.class, EncryptedStringConverter.class,
        ReservationService.class, PricingService.class, AvailabilityService.class, PaymentService.class,
        MockPaymentGateway.class, PlatformSettings.class, AuditTrail.class, SpotService.class, SpotSearchService.class,
        ServiceTestBase.FixedTime.class, NotificationEventListener.class, ReservationExpiryService.class,
        NotificationService.class})
public abstract class ServiceTestBase extends DbTestBase {

    protected static final Instant NOW = Instant.parse("2029-12-01T00:00:00Z");

    @TestConfiguration
    static class FixedTime {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        ZoneId businessZone() {
            return ZoneId.of("Europe/Warsaw");
        }
    }
}
