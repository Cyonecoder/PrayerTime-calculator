package io.github.cyonecoder.prayertime.application.provider;

import java.time.LocalDate;

/**
 * Serves prayer times for a city and date under a requested {@link PrayerSourceMode}.
 *
 * <p>The top-level {@link HabousCompatiblePrayerTimesProvider} honours every mode.
 * The single-source providers ({@link AstronomicalPrayerTimesProvider},
 * {@link HabousOfficialPrayerTimesProvider}) serve their own source regardless of
 * the requested mode, and are used as collaborators.
 */
public interface PrayerTimesProvider {

    /**
     * @param cityId     the application city slug
     * @param date       the requested civil date
     * @param sourceMode the requested source mode
     * @return the served result with full provenance metadata
     * @throws HabousDataUnavailableException in {@link PrayerSourceMode#OFFICIAL}
     *         mode when no valid official value exists (never silently calculated)
     * @throws IllegalArgumentException if the city is unknown
     */
    ProvidedTimes get(String cityId, LocalDate date, PrayerSourceMode sourceMode);
}
