package pl.pollub.pari;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pl.pollub.pari.common.domain.PlatformSetting;
import pl.pollub.pari.common.repository.PlatformSettingRepository;
import pl.pollub.pari.spot.repository.AmenityRepository;
import pl.pollub.pari.support.DbTestBase;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loading this context already proves the main thing: Flyway migrated an empty PostGIS database and
 * {@code hibernate.ddl-auto=validate} found every entity matching the schema.
 */
class SchemaMigrationTest extends DbTestBase {

    @Autowired
    PlatformSettingRepository settings;

    @Autowired
    AmenityRepository amenities;

    @Test
    void postgisAndBtreeGistAreInstalled() {
        assertThat(jdbc.queryForObject("select postgis_version()", String.class)).isNotBlank();
        assertThat(jdbc.queryForObject("select count(*) from pg_extension where extname = 'btree_gist'", Integer.class)).isEqualTo(1);
    }

    @Test
    void seedDataIsPresent() {
        assertThat(settings.findById("payout_delay_hours")).map(PlatformSetting::getValue).contains("24");
        assertThat(settings.count()).isGreaterThanOrEqualTo(5);
        assertThat(amenities.findAllByOrderByLabelAsc()).extracting("code").contains("COVERED", "EV_CHARGER", "GATE");
    }

    @Test
    void allExpectedTablesExist() {
        assertThat(jdbc.queryForList("select table_name from information_schema.tables where table_schema = 'public'", String.class))
                .contains("users", "user_roles", "auth_tokens", "vehicles", "payment_methods", "parking_spots", "spot_photos",
                        "amenities", "spot_amenities", "availability_rules", "availability_exceptions", "reservations",
                        "payments", "payouts", "reviews", "reports", "report_messages", "sanctions", "verification_requests",
                        "notifications", "premium_subscriptions", "audit_log", "platform_settings");
    }
}
