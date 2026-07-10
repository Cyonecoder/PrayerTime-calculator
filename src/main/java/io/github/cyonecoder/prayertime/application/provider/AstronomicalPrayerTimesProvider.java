package io.github.cyonecoder.prayertime.application.provider;

import io.github.cyonecoder.prayertime.audit.optimization.CalculationCandidate;
import io.github.cyonecoder.prayertime.audit.optimization.HabousRawEngine;
import io.github.cyonecoder.prayertime.audit.release.ReconstructedMethod;
import io.github.cyonecoder.prayertime.domain.model.City;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Serves {@link ProvenanceSource#ASTRONOMICAL_RECONSTRUCTED} times: the frozen
 * {@code MOROCCO_HABOUS_RECONSTRUCTED} global formula applied to a city and date.
 *
 * <p>This is the {@link PrayerSourceMode#CALCULATED} source and the safe fallback
 * for {@link PrayerSourceMode#AUTO}. It reads no official data and needs no
 * network; it is fully offline and deterministic.
 */
public final class AstronomicalPrayerTimesProvider implements PrayerTimesProvider {

    /** The reconstructed parameter version reported on calculated results. */
    public static final String PARAMETER_VERSION = "morocco-habous-v1";

    private final MoroccoCityRegistry registry;
    private final HabousRawEngine engine;
    private final CalculationCandidate method;

    public AstronomicalPrayerTimesProvider() {
        this(new MoroccoCityRegistry(), new HabousRawEngine(), ReconstructedMethod.frozen());
    }

    public AstronomicalPrayerTimesProvider(
            MoroccoCityRegistry registry, HabousRawEngine engine, CalculationCandidate method) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.method = Objects.requireNonNull(method, "method");
    }

    @Override
    public ProvidedTimes get(String cityId, LocalDate date, PrayerSourceMode sourceMode) {
        return calculated(cityId, date, sourceMode, false);
    }

    /**
     * Produces a calculated result, flagging whether it is being used as a
     * fallback (set by {@link HabousCompatiblePrayerTimesProvider} in AUTO mode).
     *
     * @param cityId       the application city slug
     * @param date         the date
     * @param sourceMode   the mode the caller asked for
     * @param fallbackUsed whether this calculation is an AUTO fallback
     * @return the calculated result
     */
    public ProvidedTimes calculated(
            String cityId, LocalDate date, PrayerSourceMode sourceMode, boolean fallbackUsed) {
        PrayerTimes times = computeTimes(cityId, date);
        return new ProvidedTimes(cityId, date, sourceMode,
                ProvenanceSource.ASTRONOMICAL_RECONSTRUCTED, false, fallbackUsed,
                null, null, null, PARAMETER_VERSION, times, null);
    }

    /**
     * Computes the six reconstructed times for a city/date.
     *
     * @param cityId the application city slug
     * @param date   the date
     * @return the calculated times
     * @throws IllegalArgumentException if the city is unknown
     */
    public PrayerTimes computeTimes(String cityId, LocalDate date) {
        City city = resolve(cityId, date);
        return new PrayerTimes(date,
                minute(Prayer.FAJR, city, date), minute(Prayer.SUNRISE, city, date),
                minute(Prayer.DHUHR, city, date), minute(Prayer.ASR, city, date),
                minute(Prayer.MAGHRIB, city, date), minute(Prayer.ISHA, city, date));
    }

    /**
     * The raw calculated event time <em>rounded to the nearest second</em> (not
     * the published minute) for a prayer. Diagnostic only: it exposes the
     * sub-minute value that publication rounding collapses, so a ±1-minute
     * difference against the official {@code HH:mm} can be understood.
     *
     * @param cityId the application city slug
     * @param date   the date
     * @param prayer the prayer
     * @return the raw+adjusted time to second precision
     */
    public LocalTime secondPreciseTime(String cityId, LocalDate date, Prayer prayer) {
        Objects.requireNonNull(prayer, "prayer");
        City city = resolve(cityId, date);
        double rawSeconds = engine.rawSecondsOfDay(prayer, method, city.coordinates(), city.zone(), date);
        long secondOfDay = Math.floorMod(
                Math.round(rawSeconds) + engine.adjustmentSeconds(prayer, method), 86_400L);
        return LocalTime.ofSecondOfDay(secondOfDay);
    }

    private City resolve(String cityId, LocalDate date) {
        Objects.requireNonNull(cityId, "cityId");
        Objects.requireNonNull(date, "date");
        return registry.findById(cityId.trim().toLowerCase(java.util.Locale.ROOT))
                .or(() -> registry.findByNameOrAlias(cityId))
                .orElseThrow(() -> new IllegalArgumentException("Unknown city: " + cityId));
    }

    private LocalTime minute(Prayer prayer, City city, LocalDate date) {
        int minuteOfDay = engine.publishedMinuteOfDay(
                prayer, method, city.coordinates(), city.zone(), date);
        return LocalTime.of(Math.floorMod(minuteOfDay, 1440) / 60, Math.floorMod(minuteOfDay, 60));
    }
}
