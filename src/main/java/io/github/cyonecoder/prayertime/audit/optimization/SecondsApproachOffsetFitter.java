package io.github.cyonecoder.prayertime.audit.optimization;

import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset.Observation;
import io.github.cyonecoder.prayertime.domain.model.City;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Fits the <em>sec-approach</em> per-city, per-prayer constant seconds offset from
 * a {@link ReferenceDataset} of official Habous observations.
 *
 * <p>The sec-approach publishes {@code floor((rawSeconds + offset) / 60)} where
 * {@code rawSeconds} is the frozen reconstructed raw astronomical time. This
 * fitter selects, for each city and prayer independently, the integer offset that
 * maximises exact-minute agreement with the official times, using the
 * <strong>identical</strong> arithmetic as the production provider so a fitted
 * offset is reproducible.
 *
 * <p>The choice is fully deterministic. Among the offsets tied for the most exact
 * matches (a contiguous plateau, because the match count as a function of offset
 * is piecewise flat), it keeps the centre of the <em>widest</em> plateau: the
 * offset furthest from a rounding boundary, i.e. the most robust to small
 * day-to-day astronomical variation. This never reads or copies a per-date
 * value — the output is one constant per city/prayer, a calculation parameter.
 *
 * <p>This is research/audit tooling: it reuses {@link HabousRawEngine} and the
 * production city model, mutates no production configuration, and reads only the
 * off-classpath research dataset.
 */
public final class SecondsApproachOffsetFitter {

    /**
     * Search half-width (seconds) for the offset. The committed offsets span
     * roughly {@code [-108, +349]}; a full hour each way leaves ample margin and
     * keeps the search exhaustive (hence deterministic) rather than heuristic.
     */
    private static final int SEARCH_HALF_WIDTH_SECONDS = 3600;

    private final MoroccoCityRegistry registry;
    private final HabousRawEngine engine;
    private final CalculationCandidate method;

    public SecondsApproachOffsetFitter(
            MoroccoCityRegistry registry, HabousRawEngine engine, CalculationCandidate method) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.method = Objects.requireNonNull(method, "method");
    }

    /**
     * The fitted offsets and in-sample accuracy for one city.
     *
     * @param cityId        the application city slug
     * @param offsets       the fitted per-prayer offset in seconds
     * @param exactByPrayer the number of exact-minute matches per prayer
     * @param dayCount      the number of official days fitted against
     */
    public record CityFit(
            String cityId,
            Map<Prayer, Integer> offsets,
            Map<Prayer, Integer> exactByPrayer,
            int dayCount) {

        /** @return total exact-minute matches across all six prayers. */
        public int exactMatches() {
            return exactByPrayer.values().stream().mapToInt(Integer::intValue).sum();
        }

        /** @return total prayer-observations fitted (six per day). */
        public int total() {
            return dayCount * Prayer.values().length;
        }

        /** @return in-sample exact-minute agreement ratio in {@code [0, 1]}. */
        public double exactRatio() {
            return total() == 0 ? 0.0 : (double) exactMatches() / total();
        }
    }

    /**
     * Fits offsets for every city present in the dataset that also resolves in the
     * city registry, in deterministic (sorted-slug) order.
     *
     * @param dataset the official reference observations
     * @return the per-city fits, keyed by slug, iteration-ordered by slug
     */
    public Map<String, CityFit> fitAll(ReferenceDataset dataset) {
        Objects.requireNonNull(dataset, "dataset");
        Map<String, List<Observation>> byCity = new LinkedHashMap<>();
        for (Observation o : dataset.observations()) {
            byCity.computeIfAbsent(o.city(), k -> new ArrayList<>()).add(o);
        }
        Map<String, CityFit> out = new LinkedHashMap<>();
        for (String city : new TreeSet<>(byCity.keySet())) {
            fit(city, byCity.get(city)).ifPresent(fit -> out.put(city, fit));
        }
        return out;
    }

    /**
     * Fits offsets for one city from its observations.
     *
     * @param cityId       the application city slug
     * @param observations that city's official days (must be non-empty)
     * @return the fit, or empty if the city does not resolve in the registry
     */
    public Optional<CityFit> fit(String cityId, List<Observation> observations) {
        Objects.requireNonNull(cityId, "cityId");
        if (observations == null || observations.isEmpty()) {
            throw new IllegalArgumentException("no observations for city: " + cityId);
        }
        Optional<City> resolved = registry.findById(normalize(cityId))
                .or(() -> registry.findByNameOrAlias(cityId));
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        City city = resolved.get();

        Map<Prayer, Integer> offsets = new EnumMap<>(Prayer.class);
        Map<Prayer, Integer> exactByPrayer = new EnumMap<>(Prayer.class);
        for (Prayer prayer : Prayer.values()) {
            PrayerFit pf = fitPrayer(prayer, city, observations);
            offsets.put(prayer, pf.offset());
            exactByPrayer.put(prayer, pf.exact());
        }
        return Optional.of(new CityFit(
                normalize(cityId), offsets, exactByPrayer, observations.size()));
    }

    private record PrayerFit(int offset, int exact) {}

    private PrayerFit fitPrayer(Prayer prayer, City city, List<Observation> observations) {
        // Precompute (rawSeconds, targetMinute) per day; the offset only shifts raw.
        long[] raw = new long[observations.size()];
        int[] target = new int[observations.size()];
        for (int i = 0; i < observations.size(); i++) {
            Observation o = observations.get(i);
            raw[i] = rawSeconds(prayer, city, o.date());
            target[i] = o.minuteOfDayFor(prayer);
        }

        // Match count is piecewise-constant in the offset; scan the whole window and
        // record, for each offset, how many days it publishes exactly.
        int width = 2 * SEARCH_HALF_WIDTH_SECONDS + 1;
        int[] matches = new int[width];
        int best = 0;
        for (int idx = 0; idx < width; idx++) {
            int offset = idx - SEARCH_HALF_WIDTH_SECONDS;
            int count = 0;
            for (int i = 0; i < raw.length; i++) {
                if (publishedMinuteOfDay(raw[i] + offset) == target[i]) {
                    count++;
                }
            }
            matches[idx] = count;
            best = Math.max(best, count);
        }

        // Among the maximal offsets, keep the centre of the widest contiguous run:
        // the offset with the largest safety margin to a rounding boundary, which
        // best resists day-to-day astronomical drift. Deterministic tie-break:
        // widest run, then the run whose centre has the smallest magnitude.
        int bestOffset = Integer.MAX_VALUE;
        int bestRunWidth = -1;
        int runStart = -1;
        for (int idx = 0; idx <= width; idx++) {
            boolean inRun = idx < width && matches[idx] == best;
            if (inRun && runStart < 0) {
                runStart = idx;
            } else if (!inRun && runStart >= 0) {
                int runEnd = idx - 1;
                int centreIdx = (runStart + runEnd) / 2;
                int centreOffset = centreIdx - SEARCH_HALF_WIDTH_SECONDS;
                int thisWidth = runEnd - runStart + 1;
                if (thisWidth > bestRunWidth
                        || (thisWidth == bestRunWidth
                            && Math.abs(centreOffset) < Math.abs(bestOffset))) {
                    bestRunWidth = thisWidth;
                    bestOffset = centreOffset;
                }
                runStart = -1;
            }
        }
        return new PrayerFit(bestOffset, best);
    }

    /** The raw reconstructed second-of-day for a prayer, rounded as the provider does. */
    private long rawSeconds(Prayer prayer, City city, LocalDate date) {
        return Math.round(
                engine.rawSecondsOfDay(prayer, method, city.coordinates(), city.zone(), date));
    }

    /** Published minute-of-day for {@code totalSeconds}, matching the provider's floor. */
    private static int publishedMinuteOfDay(long totalSeconds) {
        return Math.floorMod((int) Math.floorDiv(totalSeconds, 60L), 1440);
    }

    private static String normalize(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }

    /** Orders prayers as the sec-approach JSON does, for stable serialisation. */
    public static Comparator<Prayer> jsonOrder() {
        return Comparator.comparingInt(Prayer::ordinal);
    }
}
