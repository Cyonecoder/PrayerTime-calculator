package io.github.cyonecoder.prayertime.audit.optimization;

import java.util.Comparator;

/**
 * Aggregate quality metrics for a candidate evaluated over a set of reference
 * observations, plus the canonical ranking order.
 *
 * <p>The ranking deliberately does <strong>not</strong> sort by mean error
 * alone. In priority order a better candidate has: a higher exact-minute
 * percentage, then a lower maximum absolute error, then a lower mean absolute
 * error, then a lower error standard deviation, then fewer model parameters,
 * then higher cross-city stability.
 *
 * @param exactMinuteMatches      count of prayer×day comparisons matching the
 *                                published minute exactly
 * @param withinOneMinuteMatches  count matching to within one minute
 * @param total                   total number of comparisons
 * @param meanSignedErrorMinutes  mean of (computed - official) in minutes
 * @param meanAbsoluteErrorMinutes mean of |computed - official| in minutes
 * @param maximumAbsoluteErrorMinutes largest |computed - official| in minutes
 * @param errorStandardDeviation  population standard deviation of signed errors
 * @param cityCount               distinct cities covered
 * @param dateCount               distinct dates covered
 * @param prayerCount             distinct prayers covered
 * @param parameterCount          number of free parameters in the model
 * @param crossCityStability      the worst per-city exact fraction (0..1);
 *                                higher means more uniform across cities
 */
public record CandidateScore(
        int exactMinuteMatches,
        int withinOneMinuteMatches,
        int total,
        double meanSignedErrorMinutes,
        double meanAbsoluteErrorMinutes,
        int maximumAbsoluteErrorMinutes,
        double errorStandardDeviation,
        int cityCount,
        int dateCount,
        int prayerCount,
        int parameterCount,
        double crossCityStability) {

    /**
     * The fraction of comparisons matching the published minute exactly, as a
     * percentage in [0, 100].
     *
     * @return the exact-match percentage
     */
    public double exactPercentage() {
        return total == 0 ? 0.0 : 100.0 * exactMinuteMatches / total;
    }

    /**
     * The canonical "best first" ranking. Sorting a list ascending with this
     * comparator puts the strongest candidate at index 0.
     */
    public static final Comparator<CandidateScore> RANKING =
            Comparator.comparingDouble(CandidateScore::exactPercentage).reversed()
                    .thenComparingInt(CandidateScore::maximumAbsoluteErrorMinutes)
                    .thenComparingDouble(CandidateScore::meanAbsoluteErrorMinutes)
                    .thenComparingDouble(CandidateScore::errorStandardDeviation)
                    .thenComparingInt(CandidateScore::parameterCount)
                    .thenComparing(Comparator.comparingDouble(CandidateScore::crossCityStability).reversed());
}
