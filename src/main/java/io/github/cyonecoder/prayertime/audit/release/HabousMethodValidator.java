package io.github.cyonecoder.prayertime.audit.release;

import io.github.cyonecoder.prayertime.audit.dataset.HabousCityMapping;
import io.github.cyonecoder.prayertime.audit.model.DatasetChecksum;
import io.github.cyonecoder.prayertime.audit.model.ResidualGroup;
import io.github.cyonecoder.prayertime.audit.optimization.CalculationCandidate;
import io.github.cyonecoder.prayertime.audit.optimization.CityLocations;
import io.github.cyonecoder.prayertime.audit.optimization.HabousRawEngine;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset.Observation;
import io.github.cyonecoder.prayertime.audit.release.CorrectionsLayer.CityCorrection;
import io.github.cyonecoder.prayertime.audit.release.CorrectionsLayer.OfficialOverride;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Validates a reconstructed global method against all mapped Habous cities,
 * producing the three <em>separately reported</em> result groups required by the
 * release audit:
 * <ol>
 *   <li>{@link Group#FORMULA_ONLY} — the pure global formula;</li>
 *   <li>{@link Group#FORMULA_PLUS_CORRECTIONS} — plus approved per-city
 *       corrections;</li>
 *   <li>{@link Group#FORMULA_PLUS_CORRECTIONS_PLUS_OVERRIDES} — plus reviewed
 *       official date overrides.</li>
 * </ol>
 * The three groups are never collapsed into one figure. Corrections and
 * overrides are applied only when approved/reviewed <em>and</em> their recorded
 * source checksum still matches the city's current data; otherwise they are
 * counted as unresolved so the Habous-compatible mode can fail closed.
 */
public final class HabousMethodValidator {

    /** The five obligatory prayers plus Chourouk are all scored. */
    private static final List<Prayer> ALL_PRAYERS = List.of(
            Prayer.FAJR, Prayer.SUNRISE, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA);

    /** The three result groups. */
    public enum Group {
        FORMULA_ONLY("pure global formula"),
        FORMULA_PLUS_CORRECTIONS("global formula + approved city corrections"),
        FORMULA_PLUS_CORRECTIONS_PLUS_OVERRIDES(
                "global formula + city corrections + official date overrides");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** How a computed minute was produced. */
    public enum Source { FORMULA, CORRECTION, OVERRIDE }

    /** One computed-vs-official comparison in a group. */
    public record Comparison(
            String city, LocalDate date, Prayer prayer,
            int computedMinuteOfDay, int officialMinuteOfDay, Source source) {

        public int signedError() {
            return computedMinuteOfDay - officialMinuteOfDay;
        }

        public int absError() {
            return Math.abs(signedError());
        }
    }

    /** Aggregated metrics and per-axis breakdowns for one group. */
    public record GroupResult(
            Group group,
            List<Comparison> comparisons,
            int total,
            int exactCount,
            int withinOneCount,
            double meanSigned,
            double meanAbsolute,
            int maxAbsolute,
            Map<Prayer, ResidualGroup> byPrayer,
            Map<String, ResidualGroup> byCity,
            Map<YearMonth, ResidualGroup> byMonth,
            Map<Integer, ResidualGroup> byTimezoneOffset,
            int correctionsApplied,
            int overridesApplied,
            int unmatchedRecords) {

        public double exactPercentage() {
            return total == 0 ? 0.0 : 100.0 * exactCount / total;
        }

        public double withinOnePercentage() {
            return total == 0 ? 0.0 : 100.0 * withinOneCount / total;
        }

        /** @return the worst absolute per-city mean signed error (systematic-bias probe). */
        public double worstCityAbsMeanSigned() {
            return byCity.values().stream()
                    .mapToDouble(g -> Math.abs(g.meanSigned())).max().orElse(0.0);
        }
    }

    /** The full validation outcome across all three groups. */
    public record ValidationResult(
            String methodId,
            CalculationCandidate method,
            Map<Group, GroupResult> groups,
            List<String> includedCities,
            List<String> missingMappedCities,
            int missingDates,
            List<CityCorrection> unapprovedCorrections,
            List<OfficialOverride> unreviewedOverrides,
            List<CityCorrection> staleCorrections,
            List<OfficialOverride> staleOverrides,
            Map<String, String> cityChecksums) {

        public GroupResult group(Group g) {
            return groups.get(g);
        }
    }

    private final HabousRawEngine engine;
    private final CityLocations locations;
    private final HabousCityMapping mapping;

    public HabousMethodValidator(
            HabousRawEngine engine, CityLocations locations, HabousCityMapping mapping) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.locations = Objects.requireNonNull(locations, "locations");
        this.mapping = Objects.requireNonNull(mapping, "mapping");
    }

    /**
     * Validates the method over the dataset.
     *
     * @param methodId the method identifier (for provenance)
     * @param method   the reconstructed global candidate
     * @param dataset  the reference observations (all mapped cities)
     * @param layer    the correction/override layer
     * @return the three-group validation result
     */
    public ValidationResult validate(
            String methodId, CalculationCandidate method,
            ReferenceDataset dataset, CorrectionsLayer layer) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(layer, "layer");

        Map<String, String> cityChecksums = perCityChecksums(dataset);
        java.util.function.Function<String, String> checksumById = cityChecksums::get;

        Map<Group, GroupResult> groups = new EnumMap<>(Group.class);
        for (Group group : Group.values()) {
            groups.put(group, computeGroup(group, method, dataset, layer, checksumById));
        }

        List<String> included = dataset.cities();
        List<String> missingMapped = new ArrayList<>();
        for (String city : mapping.mappedCityIds()) {
            if (!included.contains(city)) {
                missingMapped.add(city);
            }
        }
        int missingDates = countMissingDates(dataset);

        return new ValidationResult(
                methodId, method, groups, included, missingMapped, missingDates,
                layer.unapprovedCorrections(), layer.unreviewedOverrides(),
                layer.staleCorrections(checksumById), layer.staleOverrides(checksumById),
                cityChecksums);
    }

    private GroupResult computeGroup(
            Group group, CalculationCandidate method, ReferenceDataset dataset,
            CorrectionsLayer layer, java.util.function.Function<String, String> checksumById) {
        boolean useCorrections = group != Group.FORMULA_ONLY;
        boolean useOverrides = group == Group.FORMULA_PLUS_CORRECTIONS_PLUS_OVERRIDES;

        List<Comparison> comparisons = new ArrayList<>();
        int correctionsApplied = 0;
        int overridesApplied = 0;
        int unmatched = 0;

        for (Observation obs : dataset.observations()) {
            CityLocations.Location loc;
            try {
                loc = locations.resolve(obs.city());
            } catch (RuntimeException e) {
                unmatched += ALL_PRAYERS.size();
                continue;
            }
            for (Prayer prayer : ALL_PRAYERS) {
                int official = obs.minuteOfDayFor(prayer);
                int computed = engine.publishedMinuteOfDay(
                        prayer, method, loc.coordinates(), loc.zone(), obs.date());
                Source source = Source.FORMULA;

                if (useCorrections) {
                    Optional<CityCorrection> c =
                            layer.approvedCorrection(obs.city(), prayer, checksumById);
                    if (c.isPresent()) {
                        computed = Math.floorMod(computed + c.get().deltaMinutes(), 1440);
                        source = Source.CORRECTION;
                        correctionsApplied++;
                    }
                }
                if (useOverrides) {
                    Optional<OfficialOverride> o =
                            layer.reviewedOverride(obs.city(), obs.date(), prayer, checksumById);
                    if (o.isPresent()) {
                        computed = o.get().officialMinuteOfDay();
                        source = Source.OVERRIDE;
                        overridesApplied++;
                    }
                }
                comparisons.add(new Comparison(
                        obs.city(), obs.date(), prayer, computed, official, source));
            }
        }
        return aggregate(group, comparisons, correctionsApplied, overridesApplied, unmatched);
    }

    private GroupResult aggregate(
            Group group, List<Comparison> comparisons,
            int correctionsApplied, int overridesApplied, int unmatched) {
        int total = comparisons.size();
        int exact = 0;
        int withinOne = 0;
        int maxAbs = 0;
        long sumSigned = 0;
        long sumAbs = 0;
        Map<Prayer, ResidualGroup.Accumulator> byPrayer = new EnumMap<>(Prayer.class);
        Map<String, ResidualGroup.Accumulator> byCity = new LinkedHashMap<>();
        Map<YearMonth, ResidualGroup.Accumulator> byMonth = new TreeMap<>();
        Map<Integer, ResidualGroup.Accumulator> byTz = new TreeMap<>();

        for (Comparison c : comparisons) {
            int signed = c.signedError();
            int abs = c.absError();
            if (abs == 0) {
                exact++;
            }
            if (abs <= 1) {
                withinOne++;
            }
            maxAbs = Math.max(maxAbs, abs);
            sumSigned += signed;
            sumAbs += abs;
            byPrayer.computeIfAbsent(c.prayer(), k -> new ResidualGroup.Accumulator()).add(signed);
            byCity.computeIfAbsent(c.city(), k -> new ResidualGroup.Accumulator()).add(signed);
            byMonth.computeIfAbsent(YearMonth.from(c.date()), k -> new ResidualGroup.Accumulator())
                    .add(signed);
            byTz.computeIfAbsent(timezoneOffset(c.city(), c.date()),
                    k -> new ResidualGroup.Accumulator()).add(signed);
        }

        double meanSigned = total == 0 ? 0.0 : (double) sumSigned / total;
        double meanAbs = total == 0 ? 0.0 : (double) sumAbs / total;
        return new GroupResult(group, List.copyOf(comparisons), total, exact, withinOne,
                meanSigned, meanAbs, maxAbs,
                freezeEnum(byPrayer), freeze(byCity), freeze(byMonth), freeze(byTz),
                correctionsApplied, overridesApplied, unmatched);
    }

    private int timezoneOffset(String city, LocalDate date) {
        return locations.resolve(city).zone().getRules()
                .getOffset(date.atTime(LocalTime.NOON)).getTotalSeconds() / 3600;
    }

    private Map<String, String> perCityChecksums(ReferenceDataset dataset) {
        Map<String, List<Observation>> byCity = new LinkedHashMap<>();
        for (Observation o : dataset.observations()) {
            byCity.computeIfAbsent(o.city(), k -> new ArrayList<>()).add(o);
        }
        Map<String, String> checksums = new LinkedHashMap<>();
        byCity.forEach((city, obs) -> checksums.put(city, DatasetChecksum.sha256(obs)));
        return checksums;
    }

    /** Counts internal gaps in each city's date coverage (a Habous-compatible failure). */
    private int countMissingDates(ReferenceDataset dataset) {
        Map<String, List<LocalDate>> byCity = new LinkedHashMap<>();
        for (Observation o : dataset.observations()) {
            byCity.computeIfAbsent(o.city(), k -> new ArrayList<>()).add(o.date());
        }
        int missing = 0;
        for (List<LocalDate> dates : byCity.values()) {
            dates.sort(Comparator.naturalOrder());
            for (int i = 1; i < dates.size(); i++) {
                long gap = dates.get(i).toEpochDay() - dates.get(i - 1).toEpochDay();
                if (gap > 1) {
                    missing += (int) (gap - 1);
                }
            }
        }
        return missing;
    }

    private static <K> Map<K, ResidualGroup> freeze(Map<K, ResidualGroup.Accumulator> in) {
        Map<K, ResidualGroup> out = new LinkedHashMap<>();
        in.forEach((k, acc) -> out.put(k, acc.build()));
        return out;
    }

    private static Map<Prayer, ResidualGroup> freezeEnum(Map<Prayer, ResidualGroup.Accumulator> in) {
        Map<Prayer, ResidualGroup> out = new EnumMap<>(Prayer.class);
        in.forEach((k, acc) -> out.put(k, acc.build()));
        return out;
    }
}
