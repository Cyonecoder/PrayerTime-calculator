package io.github.cyonecoder.prayertime.domain.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * The six computed clock times for a single day.
 *
 * <p>Immutable value object. All six times and the date are mandatory; any
 * {@code null} is rejected at construction.
 *
 * @param date    the civil date these times apply to
 * @param fajr    dawn prayer time
 * @param sunrise sunrise time
 * @param dhuhr   midday prayer time
 * @param asr     afternoon prayer time
 * @param maghrib sunset prayer time
 * @param isha    night prayer time
 */
public record PrayerTimes(
        LocalDate date,
        LocalTime fajr,
        LocalTime sunrise,
        LocalTime dhuhr,
        LocalTime asr,
        LocalTime maghrib,
        LocalTime isha) {

    /**
     * Validates that the date and all six times are present.
     *
     * @throws NullPointerException if any component is {@code null}
     */
    public PrayerTimes {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(fajr, "fajr");
        Objects.requireNonNull(sunrise, "sunrise");
        Objects.requireNonNull(dhuhr, "dhuhr");
        Objects.requireNonNull(asr, "asr");
        Objects.requireNonNull(maghrib, "maghrib");
        Objects.requireNonNull(isha, "isha");
    }

    /**
     * Returns the clock time for the given prayer.
     *
     * @param prayer the prayer to look up
     * @return the corresponding {@link LocalTime}
     * @throws NullPointerException if {@code prayer} is {@code null}
     */
    public LocalTime get(Prayer prayer) {
        Objects.requireNonNull(prayer, "prayer");
        return switch (prayer) {
            case FAJR -> fajr;
            case SUNRISE -> sunrise;
            case DHUHR -> dhuhr;
            case ASR -> asr;
            case MAGHRIB -> maghrib;
            case ISHA -> isha;
        };
    }
}
