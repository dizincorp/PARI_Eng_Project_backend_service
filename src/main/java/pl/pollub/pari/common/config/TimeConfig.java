package pl.pollub.pari.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class TimeConfig {

    /** All instants are UTC; services take the current time from this clock so tests can fix it. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Zone in which owners define weekly availability (local opening hours). */
    @Bean
    public ZoneId businessZone(@Value("${pari.time-zone:Europe/Warsaw}") String zone) {
        return ZoneId.of(zone);
    }
}
