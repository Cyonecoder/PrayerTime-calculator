package io.github.cyonecoder.prayertime.audit.dataset;

import java.time.Duration;
import java.util.Objects;

/**
 * Tunable politeness/robustness settings for a Habous collection run.
 *
 * @param requestDelay   delay applied before each network request
 * @param maxRetries     bounded number of retries per month on a transient error
 * @param requestTimeout per-request timeout (recorded; enforced by the client)
 * @param userAgent      explicit User-Agent (recorded; sent by the client)
 * @param refresh        when true, re-fetch even if a valid snapshot exists
 */
public record CollectionSettings(
        Duration requestDelay,
        int maxRetries,
        Duration requestTimeout,
        String userAgent,
        boolean refresh) {

    /** The default explicit User-Agent for research collection. */
    public static final String DEFAULT_USER_AGENT =
            "PrayerTime-Calculator-Research/0.1 (+https://github.com/Cyonecoder/PrayerTime-calculator)";

    public CollectionSettings {
        Objects.requireNonNull(requestDelay, "requestDelay");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(userAgent, "userAgent");
        if (requestDelay.isNegative()) {
            throw new IllegalArgumentException("requestDelay must not be negative");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0 but was " + maxRetries);
        }
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
    }

    /** @return sensible defaults: 2s delay, 3 retries, 15s timeout, no refresh. */
    public static CollectionSettings defaults() {
        return new CollectionSettings(
                Duration.ofSeconds(2), 3, Duration.ofSeconds(15), DEFAULT_USER_AGENT, false);
    }

    public CollectionSettings withRequestDelay(Duration delay) {
        return new CollectionSettings(delay, maxRetries, requestTimeout, userAgent, refresh);
    }

    public CollectionSettings withMaxRetries(int retries) {
        return new CollectionSettings(requestDelay, retries, requestTimeout, userAgent, refresh);
    }

    public CollectionSettings withRefresh(boolean value) {
        return new CollectionSettings(requestDelay, maxRetries, requestTimeout, userAgent, value);
    }
}
