package io.github.cyonecoder.prayertime.application.provider;

import com.google.gson.Gson;
import io.github.cyonecoder.prayertime.audit.optimization.CalculationCandidate;
import io.github.cyonecoder.prayertime.audit.optimization.HabousRawEngine;
import io.github.cyonecoder.prayertime.audit.release.ReconstructedMethod;
import io.github.cyonecoder.prayertime.domain.model.City;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The <em>sec-approach</em> calculation: the reconstructed raw astronomical time
 * plus a per-city, per-prayer <strong>constant seconds offset</strong>, floored
 * to the minute.
 *
 * <pre>publishedMinute = floor((rawSeconds + offsetSeconds[city][prayer]) / 60)</pre>
 *
 * <p>The offset is a city-level calculation parameter (a fixed sub-minute bias
 * and rounding boundary), <strong>not</strong> a per-date override. It lifts
 * exact agreement with the official timetable from ~81% (global formula) to ~97%.
 * Offsets are loaded from {@code /research/morocco-habous-secaproach.json};
 * cities without an entry are unsupported (this provider never guesses).
 */
public final class SecondsApproachPrayerTimesProvider {

    public static final String PARAMETER_VERSION = "morocco-habous-secaproach-v2";
    private static final String RESOURCE = "/research/morocco-habous-secaproach.json";
    private static final Gson GSON = new Gson();

    private final MoroccoCityRegistry registry;
    private final HabousRawEngine engine;
    private final CalculationCandidate method;
    private final Map<String, Map<Prayer, Integer>> offsets;

    public SecondsApproachPrayerTimesProvider() {
        this(new MoroccoCityRegistry(), new HabousRawEngine(), ReconstructedMethod.frozen());
    }

    public SecondsApproachPrayerTimesProvider(
            MoroccoCityRegistry registry, HabousRawEngine engine, CalculationCandidate method) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.method = Objects.requireNonNull(method, "method");
        this.offsets = loadOffsets();
    }

    /** @return whether sec-approach offsets exist for the city. */
    public boolean supports(String cityId) {
        return offsets.containsKey(normalize(cityId));
    }

    /**
     * The sec-approach schedule for a city/date.
     *
     * @param cityId the application city slug
     * @param date   the date
     * @return the corrected times (floored to the minute)
     * @throws IllegalArgumentException if the city is unknown or has no offsets
     */
    public PrayerTimes computeTimes(String cityId, LocalDate date) {
        City city = resolve(cityId);
        Map<Prayer, Integer> off = requireOffsets(cityId);
        return new PrayerTimes(date,
                minute(Prayer.FAJR, city, date, off), minute(Prayer.SUNRISE, city, date, off),
                minute(Prayer.DHUHR, city, date, off), minute(Prayer.ASR, city, date, off),
                minute(Prayer.MAGHRIB, city, date, off), minute(Prayer.ISHA, city, date, off));
    }

    /**
     * The raw sec-approach time <em>before</em> the floor to the minute
     * (diagnostic for {@code --seconds}): {@code rawSeconds + offset}, to the second.
     */
    public LocalTime secondPreciseTime(String cityId, LocalDate date, Prayer prayer) {
        City city = resolve(cityId);
        int offset = requireOffsets(cityId).getOrDefault(prayer, 0);
        long sec = Math.floorMod(rawSeconds(prayer, city, date) + offset, 86_400L);
        return LocalTime.ofSecondOfDay(sec);
    }

    private LocalTime minute(Prayer prayer, City city, LocalDate date, Map<Prayer, Integer> off) {
        long total = rawSeconds(prayer, city, date) + off.getOrDefault(prayer, 0);
        int minuteOfDay = Math.floorMod((int) Math.floorDiv(total, 60L), 1440);
        return LocalTime.of(minuteOfDay / 60, minuteOfDay % 60);
    }

    private long rawSeconds(Prayer prayer, City city, LocalDate date) {
        return Math.round(engine.rawSecondsOfDay(prayer, method, city.coordinates(), city.zone(), date));
    }

    private Map<Prayer, Integer> requireOffsets(String cityId) {
        Map<Prayer, Integer> off = offsets.get(normalize(cityId));
        if (off == null) {
            throw new IllegalArgumentException("no sec-approach offsets for city: " + cityId);
        }
        return off;
    }

    private City resolve(String cityId) {
        Objects.requireNonNull(cityId, "cityId");
        return registry.findById(normalize(cityId))
                .or(() -> registry.findByNameOrAlias(cityId))
                .orElseThrow(() -> new IllegalArgumentException("Unknown city: " + cityId));
    }

    private static String normalize(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, Map<Prayer, Integer>> loadOffsets() {
        try (InputStream in = SecondsApproachPrayerTimesProvider.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("sec-approach resource not found: " + RESOURCE);
            }
            OffsetsFile file = GSON.fromJson(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), OffsetsFile.class);
            // Production loads only the frozen, reviewed parameter version.
            if (file.parameterVersion != null && !PARAMETER_VERSION.equals(file.parameterVersion)) {
                throw new IllegalStateException("sec-approach parameter version mismatch: expected "
                        + PARAMETER_VERSION + " but resource is " + file.parameterVersion);
            }
            Map<String, Map<Prayer, Integer>> out = new LinkedHashMap<>();
            file.offsets.forEach((city, o) -> {
                Map<Prayer, Integer> byPrayer = new EnumMap<>(Prayer.class);
                byPrayer.put(Prayer.FAJR, o.fajr);
                byPrayer.put(Prayer.SUNRISE, o.sunrise);
                byPrayer.put(Prayer.DHUHR, o.dhuhr);
                byPrayer.put(Prayer.ASR, o.asr);
                byPrayer.put(Prayer.MAGHRIB, o.maghrib);
                byPrayer.put(Prayer.ISHA, o.isha);
                out.put(normalize(city), byPrayer);
            });
            return Map.copyOf(out);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException("reading " + RESOURCE, e);
        }
    }

    private static final class OffsetsFile {
        String parameterVersion;
        Map<String, CityOffsets> offsets;
    }

    private static final class CityOffsets {
        int fajr;
        int sunrise;
        int dhuhr;
        int asr;
        int maghrib;
        int isha;
    }
}
