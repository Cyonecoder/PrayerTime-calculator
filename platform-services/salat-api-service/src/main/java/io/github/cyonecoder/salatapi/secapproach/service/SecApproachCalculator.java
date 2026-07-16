package io.github.cyonecoder.salatapi.secapproach.service;

import io.github.cyonecoder.salatapi.city.MoroccoCityRegistry;
import io.github.cyonecoder.salatapi.city.model.City;
import io.github.cyonecoder.salatapi.common.model.Prayer;
import io.github.cyonecoder.salatapi.common.model.PrayerTimes;
import io.github.cyonecoder.salatapi.secapproach.calculation.HabousRawEngine;
import io.github.cyonecoder.salatapi.secapproach.configuration.SecApproachParameters;
import io.github.cyonecoder.salatapi.secapproach.exception.CityNotSupportedException;
import io.github.cyonecoder.salatapi.secapproach.model.CalculationCandidate;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * The released <em>sec-approach v2</em> calculation: the reconstructed raw
 * astronomical time plus a per-city, per-prayer <strong>constant seconds
 * offset</strong>, floored to the minute.
 *
 * <pre>publishedMinute = floor((rawSeconds + offsetSeconds[city][prayer]) / 60)</pre>
 *
 * <p>The offset is a city-level calculation parameter (a fixed sub-minute bias and
 * rounding boundary), <strong>not</strong> a per-date override. It lifts exact
 * agreement with the official timetable from ~81% (global formula) to ~97%.
 * Cities without an entry are unsupported: this service never guesses.
 *
 * <p><strong>Porting note.</strong> This deliberately uses only
 * {@link HabousRawEngine#rawSecondsOfDay} and applies the city offset itself. It
 * does <em>not</em> route through {@code HabousRawEngine.publishedMinuteOfDay},
 * so the reconstructed candidate's per-prayer adjustments and its rounding policy
 * are never applied here — the trained per-city offsets already absorb them.
 * Applying both would double-count the adjustment and destroy the agreement the
 * offsets were fitted for. Ported from
 * {@code application.provider.SecondsApproachPrayerTimesProvider}; see
 * {@code docs/EXTRACTION_MAP.md}.
 */
@Service
public class SecApproachCalculator {

    private static final long SECONDS_PER_DAY = 86_400L;
    private static final long SECONDS_PER_MINUTE = 60L;
    private static final long MINUTES_PER_DAY = 1440L;

    private final MoroccoCityRegistry registry;
    private final HabousRawEngine engine;
    private final CalculationCandidate method;
    private final SecApproachParameters parameters;

    public SecApproachCalculator(
            MoroccoCityRegistry registry,
            HabousRawEngine engine,
            CalculationCandidate reconstructedCandidate,
            SecApproachParameters parameters) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.method = Objects.requireNonNull(reconstructedCandidate, "reconstructedCandidate");
        this.parameters = Objects.requireNonNull(parameters, "parameters");
    }

    /** @return the released parameter version served by this calculator */
    public String parameterVersion() {
        return parameters.parameterVersion();
    }

    /** @return whether sec-approach offsets exist for the city */
    public boolean supports(String cityIdentifier) {
        return registry.findByNameOrAlias(cityIdentifier)
                .map(city -> parameters.isSupported(city.slug()))
                .orElse(false);
    }

    /**
     * The sec-approach schedule for a city/date.
     *
     * @param cityIdentifier an application city slug, name or alias
     * @param date           the date
     * @return the corrected times, floored to the minute
     * @throws CityNotSupportedException if the city is unknown or has no offsets
     */
    public PrayerTimes computeTimes(String cityIdentifier, LocalDate date) {
        City city = resolve(cityIdentifier);
        Map<Prayer, Integer> offsets = requireOffsets(city.slug());
        Objects.requireNonNull(date, "date");

        Map<Prayer, LocalTime> times = new EnumMap<>(Prayer.class);
        for (Prayer prayer : Prayer.values()) {
            times.put(prayer, minute(prayer, city, date, offsets));
        }
        return PrayerTimes.of(date, times);
    }

    /**
     * The raw sec-approach time <em>before</em> the floor to the minute:
     * {@code rawSeconds + offset}, to the second. Diagnostic only.
     */
    public LocalTime secondPreciseTime(String cityIdentifier, LocalDate date, Prayer prayer) {
        City city = resolve(cityIdentifier);
        int offset = requireOffsets(city.slug()).get(prayer);
        long adjustedSeconds = adjustedSeconds(prayer, city, date, offset);
        long secondOfDay = Math.floorMod(adjustedSeconds, SECONDS_PER_DAY);
        return LocalTime.ofSecondOfDay(secondOfDay);
    }

    private LocalTime minute(Prayer prayer, City city, LocalDate date, Map<Prayer, Integer> offsets) {
        long adjustedSeconds = adjustedSeconds(prayer, city, date, offsets.get(prayer));
        long minuteOfDay = Math.floorMod(
                Math.floorDiv(adjustedSeconds, SECONDS_PER_MINUTE), MINUTES_PER_DAY);
        return LocalTime.of((int) (minuteOfDay / 60), (int) (minuteOfDay % 60));
    }

    /**
     * {@code rawSeconds + offsetSeconds[city][prayer]} — the single constant
     * offset added to the frozen reconstructed astronomical seconds. There is no
     * per-day, monthly or seasonal correction, and no official-data lookup.
     */
    private long adjustedSeconds(Prayer prayer, City city, LocalDate date, int offsetSeconds) {
        return rawSeconds(prayer, city, date) + offsetSeconds;
    }

    private long rawSeconds(Prayer prayer, City city, LocalDate date) {
        return Math.round(engine.rawSecondsOfDay(prayer, method, city.coordinates(), city.zone(), date));
    }

    private Map<Prayer, Integer> requireOffsets(String citySlug) {
        return parameters.offsetsFor(citySlug).orElseThrow(() ->
                new CityNotSupportedException(
                        citySlug,
                        "No sec-approach offsets for city '" + citySlug
                                + "'. Only the " + parameters.count()
                                + " trained cities are supported; this city is not one of them."));
    }

    private City resolve(String cityIdentifier) {
        Objects.requireNonNull(cityIdentifier, "cityIdentifier");
        try {
            return registry.findByNameOrAlias(cityIdentifier)
                    .orElseGet(() -> registry.requireBySlug(cityIdentifier));
        } catch (NoSuchElementException e) {
            throw new CityNotSupportedException(
                    cityIdentifier, "Unknown city: " + cityIdentifier);
        }
    }
}
