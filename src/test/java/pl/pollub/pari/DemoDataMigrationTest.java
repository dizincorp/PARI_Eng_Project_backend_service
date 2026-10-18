package pl.pollub.pari;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import pl.pollub.pari.support.DbTestBase;

import static org.assertj.core.api.Assertions.assertThat;

/** The dev-only demo data in {@code db/dev} loads on top of the real schema and satisfies every constraint. */
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/dev")
class DemoDataMigrationTest extends DbTestBase {

    @Test
    void demoUsersSpotsAndRulesAreLoaded() {
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from parking_spots where status = 'ACTIVE'", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from availability_rules", Integer.class)).isEqualTo(28);
        assertThat(jdbc.queryForObject("select count(*) from spot_amenities", Integer.class)).isEqualTo(8);
    }

    @Test
    void demoSpotsAreFoundByGeographicSearch() {
        Integer nearOldTown = jdbc.queryForObject("""
                select count(*) from parking_spots
                where status = 'ACTIVE' and ST_DWithin(location, ST_MakePoint(22.5684, 51.2465)::geography, 3000)
                """, Integer.class);
        assertThat(nearOldTown).isGreaterThanOrEqualTo(3);
    }
}
