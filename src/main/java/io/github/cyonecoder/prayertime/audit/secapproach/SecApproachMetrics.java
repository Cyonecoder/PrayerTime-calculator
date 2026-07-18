package io.github.cyonecoder.prayertime.audit.secapproach;

import java.util.Comparator;
import java.util.Map;

/**
 * The evaluation of one candidate seconds-offset for a single (city, prayer)
 * against its official observations. All ranking quantities are integers, so the
 * ordering is exact and byte-for-byte reproducible — no floating-point comparison
 * enters the tie-break.
 *
 * @param offset             the candidate offset in seconds
 * @param observations       number of observations evaluated
 * @param exactCount         days whose published minute matches exactly
 * @param withinOneCount     days within +/-1 minute (circular)
 * @param sumAbsoluteError   sum of |circular minute error| over all observations
 * @param maxAbsoluteError   maximum |circular minute error|
 * @param signedError        sum of signed circular minute error (predicted - official)
 * @param errorHistogram     signed circular minute error -> count, ascending by error
 */
public record SecApproachMetrics(
        int offset,
        int observations,
        int exactCount,
        int withinOneCount,
        long sumAbsoluteError,
        int maxAbsoluteError,
        long signedError,
        Map<Integer, Integer> errorHistogram) {

    /**
     * Deterministic lexicographic ranking, best first:
     * <ol>
     *   <li>highest exact-minute count</li>
     *   <li>highest within-+/-1-minute count</li>
     *   <li>lowest mean absolute error (compared as the integer sum, since the
     *       observation count is constant within a search)</li>
     *   <li>lowest maximum absolute error</li>
     *   <li>lowest absolute offset</li>
     *   <li>lowest signed offset</li>
     * </ol>
     */
    public static final Comparator<SecApproachMetrics> BEST_FIRST =
            Comparator.comparingInt(SecApproachMetrics::exactCount).reversed()
                    .thenComparing(Comparator.comparingInt(SecApproachMetrics::withinOneCount).reversed())
                    .thenComparingLong(SecApproachMetrics::sumAbsoluteError)
                    .thenComparingInt(SecApproachMetrics::maxAbsoluteError)
                    .thenComparingInt(m -> Math.abs(m.offset()))
                    .thenComparingInt(SecApproachMetrics::offset);

    public SecApproachMetrics {
        // Sorted + unmodifiable so serialization order is deterministic (byte-for-byte).
        errorHistogram = java.util.Collections.unmodifiableSortedMap(
                new java.util.TreeMap<>(errorHistogram));
    }

    /** @return exact-minute agreement as a percentage in [0, 100]. */
    public double exactPercentage() {
        return observations == 0 ? 0.0 : 100.0 * exactCount / observations;
    }

    /** @return within-+/-1-minute agreement as a percentage in [0, 100]. */
    public double withinOnePercentage() {
        return observations == 0 ? 0.0 : 100.0 * withinOneCount / observations;
    }

    /** @return mean absolute circular minute error. */
    public double meanAbsoluteError() {
        return observations == 0 ? 0.0 : (double) sumAbsoluteError / observations;
    }

    /** @return signed mean circular minute error (predicted - official). */
    public double signedMeanError() {
        return observations == 0 ? 0.0 : (double) signedError / observations;
    }

    /** @return true if this offset is at either end of its search window. */
    public boolean touchesBoundary(int lowInclusive, int highInclusive) {
        return offset == lowInclusive || offset == highInclusive;
    }
}
