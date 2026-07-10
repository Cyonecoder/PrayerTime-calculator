package io.github.cyonecoder.prayertime.domain.model;

/**
 * The six daily times produced by the calculator, in chronological order.
 *
 * <p>Note that {@code SUNRISE} is not a prayer but is included because it marks
 * the end of the Fajr window and is part of the standard published schedule.
 */
public enum Prayer {
    /** Dawn prayer. */
    FAJR,
    /** Sunrise (end of the Fajr window). */
    SUNRISE,
    /** Midday prayer. */
    DHUHR,
    /** Afternoon prayer. */
    ASR,
    /** Sunset prayer. */
    MAGHRIB,
    /** Night prayer. */
    ISHA
}
