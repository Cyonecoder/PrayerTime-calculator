package io.github.cyonecoder.salatapi.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed configuration for accessing the official Habous source (spec 11, 14, 26),
 * bound from {@code salat.habous.*}.
 *
 * <p>Every limit is configurable via an environment variable, but the defaults are
 * chosen to be respectful of a government site: one request at a time, spaced
 * apart, with bounded attempts. TLS verification is never disabled and no access
 * control is ever bypassed.
 *
 * @param enabled            whether daily collection is scheduled ({@code HABOUS_SYNC_ENABLED})
 * @param baseUri            the verified timetable endpoint, taking only a {@code ville} parameter
 * @param villeDirectoryUrl  the page whose {@code <select name="ville">} lists every official ville
 * @param syncCron           the daily sync cron ({@code Africa/Casablanca})
 * @param connectionTimeout  TCP connect timeout
 * @param requestTimeout     per-request timeout
 * @param maximumAttempts    total attempts per city, including the first
 * @param maximumConcurrency simultaneous requests to the official site; 1 by default
 * @param requestDelay       delay between consecutive requests
 * @param userAgent          the explicit User-Agent identifying this client
 * @param retryInitialBackoff base delay for the first retry; doubles per attempt
 * @param retryMaxBackoff    ceiling for the exponential backoff
 */
@ConfigurationProperties(prefix = "salat.habous")
public record HabousProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("https://www.habous.gov.ma/prieres/horaire_hijri_2.php") String baseUri,
        @DefaultValue("https://www.habous.gov.ma/prieres/index.php") String villeDirectoryUrl,
        @DefaultValue("0 15 3 * * *") String syncCron,
        @DefaultValue("10s") Duration connectionTimeout,
        @DefaultValue("15s") Duration requestTimeout,
        @DefaultValue("3") int maximumAttempts,
        @DefaultValue("1") int maximumConcurrency,
        @DefaultValue("500ms") Duration requestDelay,
        @DefaultValue("SalatApiService/1.0") String userAgent,
        @DefaultValue("1s") Duration retryInitialBackoff,
        @DefaultValue("30s") Duration retryMaxBackoff) {

    public HabousProperties {
        if (maximumConcurrency < 1) {
            throw new IllegalArgumentException("maximumConcurrency must be >= 1 but was " + maximumConcurrency);
        }
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException("maximumAttempts must be >= 1 but was " + maximumAttempts);
        }
        if (requestDelay.isNegative()) {
            throw new IllegalArgumentException("requestDelay must not be negative");
        }
        if (retryInitialBackoff.isNegative() || retryMaxBackoff.isNegative()) {
            throw new IllegalArgumentException("retry backoff must not be negative");
        }
    }

    /**
     * A test-friendly factory that fills the fixed fields (concurrency 1, cron and
     * retry backoffs at their defaults), so tests are not coupled to the full field
     * order.
     */
    public static HabousProperties forTests(
            String baseUri, Duration requestDelay, Duration connectionTimeout,
            Duration requestTimeout, int maximumAttempts, String userAgent) {
        return new HabousProperties(
                true, baseUri, baseUri, "0 15 3 * * *",
                connectionTimeout, requestTimeout, maximumAttempts, 1, requestDelay, userAgent,
                Duration.ofSeconds(1), Duration.ofSeconds(30));
    }
}
