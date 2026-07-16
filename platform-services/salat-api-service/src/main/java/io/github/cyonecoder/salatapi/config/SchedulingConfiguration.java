package io.github.cyonecoder.salatapi.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Time and scheduling wiring.
 *
 * <p>The clock is a bean so nothing reaches for {@code Instant.now()} directly:
 * discovery timestamps, snapshot provenance and verification report dates are all
 * observable facts that tests must be able to fix.
 */
@Configuration
public class SchedulingConfiguration {

    /** The civil zone every Moroccan prayer time and sync date is expressed in. */
    public static final ZoneId MOROCCO_ZONE = ZoneId.of("Africa/Casablanca");

    /**
     * @return a clock in {@code Africa/Casablanca}, so "today" means today in
     *         Morocco regardless of where the service runs
     */
    @Bean
    public Clock clock() {
        return Clock.system(MOROCCO_ZONE);
    }
}
