package io.github.cyonecoder.salatapi.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates Spring's scheduler, so {@code @Scheduled} methods fire.
 *
 * <p>Enabled by default; turn it off with {@code salat.scheduling.enabled=false}
 * for tests, or for a read-only replica that must never collect. When off, the
 * daily job bean still exists (and can be triggered manually) but its cron never
 * fires.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "salat.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerActivation {
}
