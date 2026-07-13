package io.github.cyonecoder.prayertime.audit.secapproach;

import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachTrainingDataset.TrainingObservation;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Trains the sec-approach per-city, per-prayer seconds offsets deterministically:
 * for each (city, prayer) it runs {@link SecApproachOffsetSearch} and keeps the
 * winning offset, then admits a city only if its in-sample exact-minute agreement
 * meets the band. Cities already present in the supplied base are preserved
 * verbatim (their shipped output never changes) and only re-scored for the report.
 *
 * <p>The whole process is a discrete integer optimization: no randomness, no
 * floating-point tie-break, no per-date fitting. The same dataset yields the same
 * offsets every time.
 */
public final class SecApproachTrainer {

    /** One prayer's trained result for a city. */
    public record PrayerResult(
            Prayer prayer, int offset, SecApproachMetrics metrics, boolean fromBase) {
    }

    /** One city's trained result. */
    public record CityResult(
            String city, String snapshotChecksum, int dayCount,
            List<PrayerResult> prayers, boolean preserved) {

        public CityResult {
            prayers = List.copyOf(prayers);
        }

        public int exactMatches() {
            return prayers.stream().mapToInt(p -> p.metrics().exactCount()).sum();
        }

        public int total() {
            return dayCount * Prayer.values().length;
        }

        public double exactRatio() {
            return total() == 0 ? 0.0 : (double) exactMatches() / total();
        }

        /** @return true if any freshly-searched prayer's offset sits on the boundary. */
        public boolean touchesBoundary(int low, int high) {
            return prayers.stream()
                    .anyMatch(p -> !p.fromBase() && p.metrics().touchesBoundary(low, high));
        }

        public Map<Prayer, Integer> offsets() {
            Map<Prayer, Integer> out = new EnumMap<>(Prayer.class);
            for (PrayerResult p : prayers) {
                out.put(p.prayer(), p.offset());
            }
            return out;
        }
    }

    /** The full training outcome. */
    public record Outcome(
            List<CityResult> preserved,
            List<CityResult> written,
            List<CityResult> belowBand,
            List<String> unresolved,
            int low,
            int high,
            double minExact) {

        /** @return final offsets (preserved base + newly written), ordered by slug. */
        public Map<String, Map<Prayer, Integer>> offsets() {
            Map<String, Map<Prayer, Integer>> out = new TreeMap<>();
            preserved.forEach(c -> out.put(c.city(), c.offsets()));
            written.forEach(c -> out.put(c.city(), c.offsets()));
            return out;
        }

        /** @return cities whose winning offset touched a search boundary. */
        public List<CityResult> boundaryTouches() {
            List<CityResult> hits = new ArrayList<>();
            preserved.forEach(c -> {
                if (c.touchesBoundary(low, high)) {
                    hits.add(c);
                }
            });
            written.forEach(c -> {
                if (c.touchesBoundary(low, high)) {
                    hits.add(c);
                }
            });
            belowBand.forEach(c -> {
                if (c.touchesBoundary(low, high)) {
                    hits.add(c);
                }
            });
            return hits;
        }
    }

    private final int low;
    private final int high;
    private final double minExact;

    public SecApproachTrainer(int low, int high, double minExact) {
        if (low > high) {
            throw new IllegalArgumentException("low > high");
        }
        if (minExact < 0.0 || minExact > 1.0) {
            throw new IllegalArgumentException("minExact must be in [0,1]");
        }
        this.low = low;
        this.high = high;
        this.minExact = minExact;
    }

    /**
     * Trains over the dataset, preserving any city present in {@code base}.
     *
     * @param dataset the training dataset
     * @param base    already-shipped offsets to preserve verbatim (may be empty)
     * @return the training outcome
     */
    public Outcome train(SecApproachTrainingDataset dataset, Map<String, Map<Prayer, Integer>> base) {
        Objects.requireNonNull(dataset, "dataset");
        Map<String, Map<Prayer, Integer>> baseOffsets = base == null ? Map.of() : base;

        List<CityResult> preserved = new ArrayList<>();
        List<CityResult> written = new ArrayList<>();
        List<CityResult> belowBand = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();

        for (String city : dataset.cities()) {
            List<TrainingObservation> probe = dataset.observations(city, Prayer.FAJR);
            if (probe.isEmpty()) {
                unresolved.add(city);
                continue;
            }
            int dayCount = probe.size();
            String checksum = dataset.checksum(city);

            if (baseOffsets.containsKey(city)) {
                preserved.add(scoreCity(dataset, city, dayCount, checksum, baseOffsets.get(city)));
                continue;
            }
            CityResult trained = trainCity(dataset, city, dayCount, checksum);
            if (trained.exactRatio() >= minExact) {
                written.add(trained);
            } else {
                belowBand.add(trained);
            }
        }
        return new Outcome(List.copyOf(preserved), List.copyOf(written), List.copyOf(belowBand),
                List.copyOf(unresolved), low, high, minExact);
    }

    private CityResult trainCity(
            SecApproachTrainingDataset dataset, String city, int dayCount, String checksum) {
        List<PrayerResult> prayers = new ArrayList<>();
        for (Prayer prayer : Prayer.values()) {
            SecApproachOffsetSearch.Result r =
                    SecApproachOffsetSearch.search(dataset.observations(city, prayer), low, high);
            prayers.add(new PrayerResult(prayer, r.best().offset(), r.best(), false));
        }
        return new CityResult(city, checksum, dayCount, prayers, false);
    }

    private CityResult scoreCity(
            SecApproachTrainingDataset dataset, String city, int dayCount, String checksum,
            Map<Prayer, Integer> baseOffsets) {
        List<PrayerResult> prayers = new ArrayList<>();
        for (Prayer prayer : Prayer.values()) {
            int offset = baseOffsets.getOrDefault(prayer, 0);
            SecApproachMetrics metrics =
                    SecApproachEvaluator.evaluate(offset, dataset.observations(city, prayer));
            prayers.add(new PrayerResult(prayer, offset, metrics, true));
        }
        return new CityResult(city, checksum, dayCount, prayers, true);
    }
}
