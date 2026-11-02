package pl.pollub.pari.devdata;

import org.junit.jupiter.api.Test;
import pl.pollub.pari.support.DbTestBase;

import static org.assertj.core.api.Assertions.assertThat;

/** The clean (default) database variant: only the migrations run, so there are no users or spots, just the reference data the app needs. */
class EmptyDatabaseTest extends DbTestBase {

    @Test
    void onlyTheReferenceDataIsThere() {
        for (String table : new String[]{"users", "user_roles", "vehicles", "payment_methods", "parking_spots", "reservations",
                "payments", "payouts", "withdrawals", "reviews", "notifications", "premium_subscriptions", "verification_requests"}) {
            assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
        }
        assertThat(jdbc.queryForObject("select count(*) from amenities", Long.class)).isEqualTo(7);
        assertThat(jdbc.queryForList("select setting_key from platform_settings", String.class))
                .contains("commission_rate", "service_fee_rate", "payout_delay_hours", "approval_timeout_minutes", "discount_3_months_percent");
    }
}
