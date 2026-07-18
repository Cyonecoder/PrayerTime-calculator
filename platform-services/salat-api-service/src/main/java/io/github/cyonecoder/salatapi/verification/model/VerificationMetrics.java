package io.github.cyonecoder.salatapi.verification.model;

import java.util.List;

/**
 * Accuracy metrics over a set of per-prayer differences (spec 22), where each
 * difference is {@code secApproachMinute - officialMinute}.
 *
 * <p>The same shape is reported overall, per city and per prayer, so a reader can
 * see both the aggregate and where any disagreement concentrates.
 *
 * @param sampleCount             number of prayer comparisons
 * @param exactCount              comparisons agreeing to the minute
 * @param exactRatio              {@code exactCount / sampleCount}, 0..1
 * @param withinOneCount          comparisons within one minute either way
 * @param withinOneRatio          {@code withinOneCount / sampleCount}, 0..1
 * @param meanAbsoluteDifference  mean of the absolute differences, in minutes
 * @param maxAbsoluteDifference   largest absolute difference, in minutes
 */
public record VerificationMetrics(
        int sampleCount,
        int exactCount,
        double exactRatio,
        int withinOneCount,
        double withinOneRatio,
        double meanAbsoluteDifference,
        int maxAbsoluteDifference) {

    /**
     * @param differenceMinutes the signed {@code secApproach - official} minute
     *                          differences to aggregate
     * @return the metrics over those differences
     */
    public static VerificationMetrics over(List<Integer> differenceMinutes) {
        int n = differenceMinutes.size();
        if (n == 0) {
            return new VerificationMetrics(0, 0, 0.0, 0, 0.0, 0.0, 0);
        }
        int exact = 0;
        int withinOne = 0;
        long sumAbs = 0;
        int maxAbs = 0;
        for (int diff : differenceMinutes) {
            int abs = Math.abs(diff);
            if (diff == 0) {
                exact++;
            }
            if (abs <= 1) {
                withinOne++;
            }
            sumAbs += abs;
            maxAbs = Math.max(maxAbs, abs);
        }
        return new VerificationMetrics(
                n, exact, (double) exact / n, withinOne, (double) withinOne / n,
                (double) sumAbs / n, maxAbs);
    }
}
