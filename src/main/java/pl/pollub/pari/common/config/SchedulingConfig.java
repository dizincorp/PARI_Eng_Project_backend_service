package pl.pollub.pari.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Switches on {@code @Scheduled} jobs. Tests turn it off with {@code pari.scheduling.enabled=false}. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "pari.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
