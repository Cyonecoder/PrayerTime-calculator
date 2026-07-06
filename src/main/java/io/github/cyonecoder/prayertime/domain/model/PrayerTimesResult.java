package io.github.cyonecoder.prayertime.domain.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * The outcome of a prayer-time lookup, together with provenance metadata about
 * how and when it was produced.
 *
 * <p>Immutable value object. All components are mandatory.
 *
 * @param prayerTimes the computed times
 * @param source      the source that produced them (e.g. {@code "astronomical"}
 *                    or {@code "habous"})
 * @param methodName  the calculation method name (e.g. {@code "Morocco (Habous)"})
 * @param generatedAt the instant the result was generated
 * @param timezone    the civil time zone the times are expressed in
 * @param utcOffset   the UTC offset that was in effect for that date/zone
 */
public record PrayerTimesResult(
        PrayerTimes prayerTimes,
        String source,
        String methodName,
        Instant generatedAt,
        ZoneId timezone,
        ZoneOffset utcOffset) {

    /**
     * Validates that all components are present.
     *
     * @throws NullPointerException if any component is {@code null}
     */
    public PrayerTimesResult {
        Objects.requireNonNull(prayerTimes, "prayerTimes");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(timezone, "timezone");
        Objects.requireNonNull(utcOffset, "utcOffset");
    }
}
