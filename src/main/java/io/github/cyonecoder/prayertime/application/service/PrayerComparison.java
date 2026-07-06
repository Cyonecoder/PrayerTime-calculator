package io.github.cyonecoder.prayertime.application.service;

import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.time.LocalTime;
import java.util.Objects;

/**
 * A single prayer's calculated-vs-official comparison.
 *
 * <p>The signed difference is {@code calculated - official} in whole minutes,
 * computed in a midnight-safe way (wrapped into {@code (-720, 720]} so a time
 * just after midnight compared to one just before midnight yields a small
 * difference, not ~1440). The absolute difference is its magnitude.
 *
 * @param prayer                    the prayer being compared
 * @param calculated                the astronomically calculated time
 * @param official                  the official (Habous) time
 * @param signedDifferenceMinutes   calculated - official, midnight-safe minutes
 * @param absoluteDifferenceMinutes {@code |signedDifferenceMinutes|}
 * @param withinTolerance           whether the absolute difference is within
 *                                  the configured tolerance
 */
public record PrayerComparison(
        Prayer prayer,
        LocalTime calculated,
        LocalTime official,
        long signedDifferenceMinutes,
        long absoluteDifferenceMinutes,
        boolean withinTolerance) {

    private static final int MINUTES_PER_DAY = 1_440;
    private static final int HALF_DAY_MINUTES = 720;

    public PrayerComparison {
        Objects.requireNonNull(prayer, "prayer");
        Objects.requireNonNull(calculated, "calculated");
        Objects.requireNonNull(official, "official");
    }

    /**
     * Builds a comparison for one prayer.
     *
     * @param prayer           the prayer
     * @param calculated       the calculated time
     * @param official         the official time
     * @param toleranceMinutes the allowed absolute difference in minutes
     * @return the comparison
     */
    public static PrayerComparison of(
            Prayer prayer, LocalTime calculated, LocalTime official, int toleranceMinutes) {
        long signed = signedDifferenceMinutes(calculated, official);
        long absolute = Math.abs(signed);
        return new PrayerComparison(
                prayer, calculated, official, signed, absolute, absolute <= toleranceMinutes);
    }

    /** Midnight-safe {@code calculated - official} in whole minutes, in (-720, 720]. */
    static long signedDifferenceMinutes(LocalTime calculated, LocalTime official) {
        long raw = (long) minuteOfDay(calculated) - minuteOfDay(official);
        long wrapped = ((raw % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY; // [0, 1440)
        if (wrapped > HALF_DAY_MINUTES) {
            wrapped -= MINUTES_PER_DAY; // (-720, 720]
        }
        return wrapped;
    }

    private static int minuteOfDay(LocalTime time) {
        return Math.round(time.toSecondOfDay() / 60.0f);
    }
}
