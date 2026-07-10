package io.github.cyonecoder.prayertime.application.service;

import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimesResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Compares astronomically calculated prayer times against the official (Habous)
 * timetable without ever merging the two sources.
 *
 * <p>The five obligatory prayers (Fajr, Dhuhr, Asr, Maghrib, Isha) drive the
 * pass/fail result and the aggregate error figures. Sunrise is compared and
 * reported <em>separately</em> and never affects the pass/fail decision.
 *
 * <p>Signs are preserved per prayer before any aggregation: the mean and
 * maximum are computed over <em>absolute</em> differences (mean absolute error /
 * maximum absolute error), never over averaged signed values.
 */
public final class PrayerTimeComparisonService {

    /** The five obligatory prayers that determine pass/fail (Sunrise excluded). */
    private static final List<Prayer> OBLIGATORY_PRAYERS =
            List.of(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA);

    /** Per-prayer tolerance, in minutes. */
    public record Tolerances(int fajr, int sunrise, int dhuhr, int asr, int maghrib, int isha) {

        /** Default: 2 minutes for every prayer (Sunrise reported separately). */
        public static Tolerances defaults() {
            return new Tolerances(2, 2, 2, 2, 2, 2);
        }

        public int minutesFor(Prayer prayer) {
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

    /** The outcome of a comparison: either a full report or "official unavailable". */
    public sealed interface ComparisonResult
            permits ComparisonResult.Compared, ComparisonResult.OfficialUnavailable {

        /**
         * A completed comparison.
         *
         * @param calculatedSource          label of the calculated source
         * @param officialSource            label of the official source, taken
         *                                  verbatim (e.g. {@code "habous-official"})
         *                                  — never fabricated as "live"
         * @param prayerComparisons         the five obligatory prayers
         * @param sunriseComparison         sunrise, reported separately
         * @param meanAbsoluteErrorMinutes  MAE over the five prayers
         * @param maxAbsoluteErrorMinutes   max absolute error over the five prayers
         * @param allWithinTolerance        whether all five are within tolerance
         */
        record Compared(
                String calculatedSource,
                String officialSource,
                List<PrayerComparison> prayerComparisons,
                PrayerComparison sunriseComparison,
                double meanAbsoluteErrorMinutes,
                long maxAbsoluteErrorMinutes,
                boolean allWithinTolerance) implements ComparisonResult {
        }

        /**
         * Official data was not available; no comparison was made.
         *
         * @param calculatedSource the calculated source that was available
         * @param reason           human-readable reason
         */
        record OfficialUnavailable(String calculatedSource, String reason)
                implements ComparisonResult {
        }
    }

    private final Tolerances tolerances;

    public PrayerTimeComparisonService() {
        this(Tolerances.defaults());
    }

    public PrayerTimeComparisonService(Tolerances tolerances) {
        this.tolerances = Objects.requireNonNull(tolerances, "tolerances");
    }

    /**
     * Compares calculated times against optional official times.
     *
     * @param calculated       the calculated prayer times
     * @param calculatedSource label for the calculated source
     * @param official         the official result, or empty if unavailable
     * @return a {@link ComparisonResult}: {@code Compared} or
     *         {@code OfficialUnavailable}
     */
    public ComparisonResult compare(
            PrayerTimes calculated, String calculatedSource, Optional<PrayerTimesResult> official) {
        Objects.requireNonNull(calculated, "calculated");
        Objects.requireNonNull(calculatedSource, "calculatedSource");
        Objects.requireNonNull(official, "official");

        if (official.isEmpty()) {
            return new ComparisonResult.OfficialUnavailable(
                    calculatedSource, "No official Habous data available");
        }

        PrayerTimesResult officialResult = official.get();
        PrayerTimes officialTimes = officialResult.prayerTimes();

        List<PrayerComparison> prayerComparisons = new ArrayList<>();
        long sumAbsolute = 0;
        long maxAbsolute = 0;
        boolean allWithin = true;
        for (Prayer prayer : OBLIGATORY_PRAYERS) {
            PrayerComparison c = comparisonFor(prayer, calculated, officialTimes);
            prayerComparisons.add(c);
            sumAbsolute += c.absoluteDifferenceMinutes();
            maxAbsolute = Math.max(maxAbsolute, c.absoluteDifferenceMinutes());
            allWithin = allWithin && c.withinTolerance();
        }
        double meanAbsolute = (double) sumAbsolute / OBLIGATORY_PRAYERS.size();

        PrayerComparison sunrise = comparisonFor(Prayer.SUNRISE, calculated, officialTimes);

        return new ComparisonResult.Compared(
                calculatedSource,
                officialResult.source(),
                List.copyOf(prayerComparisons),
                sunrise,
                meanAbsolute,
                maxAbsolute,
                allWithin);
    }

    private PrayerComparison comparisonFor(
            Prayer prayer, PrayerTimes calculated, PrayerTimes official) {
        return PrayerComparison.of(
                prayer, calculated.get(prayer), official.get(prayer), tolerances.minutesFor(prayer));
    }
}
