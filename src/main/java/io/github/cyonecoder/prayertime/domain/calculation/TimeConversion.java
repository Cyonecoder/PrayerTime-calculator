package io.github.cyonecoder.prayertime.domain.calculation;

import java.time.LocalTime;

/**
 * Converts a decimal-hours value (e.g. 13.5 = 13:30) into a {@link LocalTime}.
 *
 * <p>Conversion always <em>rounds</em> to the nearest unit rather than
 * truncating, and normalizes out-of-range inputs into a single day: negative
 * values wrap up from the previous day and values &ge; 24h wrap down, using
 * floor-modulo so the result is always a valid time of day.
 */
public final class TimeConversion {

    private static final int SECONDS_PER_MINUTE = 60;
    private static final int MINUTES_PER_HOUR = 60;
    private static final int SECONDS_PER_HOUR = 3_600;
    private static final int SECONDS_PER_DAY = 86_400;
    private static final int MINUTES_PER_DAY = 1_440;

    /**
     * Converts decimal hours to a {@link LocalTime}, rounded to the nearest
     * second.
     *
     * @param decimalHours the time as decimal hours (may be negative or &ge; 24)
     * @return the normalized {@link LocalTime}
     * @throws IllegalArgumentException if {@code decimalHours} is non-finite
     */
    public LocalTime toLocalTime(double decimalHours) {
        requireFinite(decimalHours);
        long second = Math.round(decimalHours * SECONDS_PER_HOUR);
        return LocalTime.ofSecondOfDay(Math.floorMod(second, SECONDS_PER_DAY));
    }

    /**
     * Converts decimal hours to a {@link LocalTime}, rounded to the nearest
     * whole minute (seconds are always zero).
     *
     * @param decimalHours the time as decimal hours (may be negative or &ge; 24)
     * @return the normalized {@link LocalTime} on a minute boundary
     * @throws IllegalArgumentException if {@code decimalHours} is non-finite
     */
    public LocalTime toLocalTimeRoundedToMinute(double decimalHours) {
        return toLocalTimeRoundedToMinutes(decimalHours, 1);
    }

    /**
     * Converts decimal hours to a {@link LocalTime}, rounded to the nearest
     * multiple of {@code stepMinutes} (explicit minute rounding).
     *
     * @param decimalHours the time as decimal hours (may be negative or &ge; 24)
     * @param stepMinutes  the rounding step in minutes (&ge; 1)
     * @return the normalized {@link LocalTime} on the requested boundary
     * @throws IllegalArgumentException if {@code decimalHours} is non-finite or
     *                                  {@code stepMinutes < 1}
     */
    public LocalTime toLocalTimeRoundedToMinutes(double decimalHours, int stepMinutes) {
        requireFinite(decimalHours);
        if (stepMinutes < 1) {
            throw new IllegalArgumentException("stepMinutes must be >= 1 but was " + stepMinutes);
        }
        long totalMinutes = Math.round(decimalHours * MINUTES_PER_HOUR);
        long roundedMinutes = Math.round(totalMinutes / (double) stepMinutes) * (long) stepMinutes;
        long minuteOfDay = Math.floorMod(roundedMinutes, MINUTES_PER_DAY);
        return LocalTime.ofSecondOfDay(minuteOfDay * SECONDS_PER_MINUTE);
    }

    private static void requireFinite(double decimalHours) {
        if (Double.isNaN(decimalHours) || Double.isInfinite(decimalHours)) {
            throw new IllegalArgumentException(
                    "decimalHours must be a finite number but was " + decimalHours);
        }
    }
}
