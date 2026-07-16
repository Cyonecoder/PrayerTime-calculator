package io.github.cyonecoder.salatapi.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for accessing the official Habous source (spec 11 and 14).
 *
 * <p>Every limit is configurable, but the defaults are chosen to be respectful of
 * a government site: one request at a time, spaced apart, with bounded attempts.
 * TLS verification is never disabled and no access control is ever bypassed.
 *
 * @param baseUrl            the verified timetable endpoint, taking only a {@code ville} parameter
 * @param villeDirectoryUrl  the page whose {@code <select name="ville">} lists every official ville
 * @param userAgent          the explicit User-Agent identifying this client
 * @param maxConcurrency     simultaneous requests to the official site; 1 by default
 * @param requestDelay       delay between consecutive requests
 * @param connectTimeout     TCP connect timeout
 * @param requestTimeout     per-request timeout
 * @param maxAttempts        total attempts per city, including the first; 3 by default
 * @param retryInitialBackoff base delay for the first retry; doubles per attempt
 * @param retryMaxBackoff    ceiling for the exponential backoff
 */
@ConfigurationProperties(prefix = "habous")
public record HabousProperties(
        @DefaultValue("https://www.habous.gov.ma/prieres/horaire_hijri_2.php") String baseUrl,
        @DefaultValue("https://www.habous.gov.ma/prieres/index.php") String villeDirectoryUrl,
        @DefaultValue("PrayerTime-Calculator/0.1 (+https://github.com/Cyonecoder/PrayerTime-calculator)")
                String userAgent,
        @DefaultValue("1") int maxConcurrency,
        @DefaultValue("500ms") Duration requestDelay,
        @DefaultValue("10s") Duration connectTimeout,
        @DefaultValue("15s") Duration requestTimeout,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("1s") Duration retryInitialBackoff,
        @DefaultValue("30s") Duration retryMaxBackoff) {

    public HabousProperties {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be >= 1 but was " + maxConcurrency);
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1 but was " + maxAttempts);
        }
        if (requestDelay.isNegative()) {
            throw new IllegalArgumentException("requestDelay must not be negative");
        }
        if (retryInitialBackoff.isNegative() || retryMaxBackoff.isNegative()) {
            throw new IllegalArgumentException("retry backoff must not be negative");
        }
    }
}
