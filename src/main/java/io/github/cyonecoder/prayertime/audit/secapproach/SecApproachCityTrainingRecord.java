package io.github.cyonecoder.prayertime.audit.secapproach;

import java.util.List;
import java.util.Map;

/**
 * The independent training result for one candidate city, in the fixed catalog
 * schema. A non-{@link Status#ACCEPTED} city carries no offsets — failed cities
 * are never given fabricated parameters, and the sec-approach provider keeps
 * failing closed for them.
 */
public final class SecApproachCityTrainingRecord {

    /** The outcome of training one city. */
    public enum Status {
        ACCEPTED,
        INSUFFICIENT_DATA,
        MAPPING_UNVERIFIED,
        FAILED_ACCURACY_GATE,
        SUSPICIOUS_OFFSET,
        MANUAL_REVIEW_REQUIRED
    }

    /** Aggregate metrics over the six prayers of one chronological segment. */
    public record SegmentMetrics(
            int observations,
            int exactCount,
            double exactPercentage,
            int withinOneCount,
            double meanAbsoluteError,
            int maxAbsoluteError) {

        /** Sums the per-prayer metrics of one segment into a single aggregate. */
        public static SegmentMetrics aggregate(List<SecApproachMetrics> perPrayer) {
            int obs = 0;
            int exact = 0;
            int within = 0;
            long sumAbs = 0;
            int maxAbs = 0;
            for (SecApproachMetrics m : perPrayer) {
                obs += m.observations();
                exact += m.exactCount();
                within += m.withinOneCount();
                sumAbs += m.sumAbsoluteError();
                maxAbs = Math.max(maxAbs, m.maxAbsoluteError());
            }
            double exactPct = obs == 0 ? 0.0 : round2(100.0 * exact / obs);
            double mae = obs == 0 ? 0.0 : round3((double) sumAbs / obs);
            return new SegmentMetrics(obs, exact, exactPct, within, mae, maxAbs);
        }

        private static double round2(double v) {
            return Math.round(v * 100.0) / 100.0;
        }

        private static double round3(double v) {
            return Math.round(v * 1000.0) / 1000.0;
        }
    }

    /** The dataset provenance summary for one city. */
    public record DatasetInfo(
            String firstDate,
            String lastDate,
            int observationCount,
            List<String> sourceChecksums) {

        public DatasetInfo {
            sourceChecksums = List.copyOf(sourceChecksums);
        }
    }

    /**
     * One city's catalog record. Metrics and offsets are {@code null} when the
     * city was not trained (no data / unverified / needs review), so they are
     * omitted from JSON rather than fabricated.
     *
     * @param cityId            application city slug
     * @param dataset           dataset provenance
     * @param offsetsSeconds    the six accepted offsets (null unless ACCEPTED)
     * @param trainingMetrics   training-segment metrics (null if not trained)
     * @param validationMetrics validation-segment metrics (null if not trained)
     * @param holdoutMetrics    holdout-segment metrics, out-of-sample (null if not trained)
     * @param fullDatasetMetrics in-sample all-dates metrics (null if not trained)
     * @param status            the training status
     */
    public record CityTrainingRecord(
            String cityId,
            DatasetInfo dataset,
            Map<String, Integer> offsetsSeconds,
            SegmentMetrics trainingMetrics,
            SegmentMetrics validationMetrics,
            SegmentMetrics holdoutMetrics,
            SegmentMetrics fullDatasetMetrics,
            String status) {
    }

    private SecApproachCityTrainingRecord() {
    }
}
