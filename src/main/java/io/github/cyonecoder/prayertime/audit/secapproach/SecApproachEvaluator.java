package io.github.cyonecoder.prayertime.audit.secapproach;

import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachTrainingDataset.TrainingObservation;
import java.util.List;
import java.util.TreeMap;

/**
 * Scores a candidate seconds-offset against a (city, prayer)'s observations.
 *
 * <p>The published minute uses the exact production arithmetic
 * {@code predictedMinute(o) = floorMod(floorDiv(rawSeconds + o, 60), 1440)}, and
 * the error is the <em>circular</em> (midnight-safe) minute difference in
 * {@code [-720, 719]}, so 00:00 vs 23:59 counts as one minute, not 1439.
 */
public final class SecApproachEvaluator {

    private static final int MINUTES_PER_DAY = 1440;
    private static final int HALF_DAY = 720;

    private SecApproachEvaluator() {
    }

    /** The published minute-of-day for a raw second-of-day and candidate offset. */
    public static int predictedMinute(long rawSeconds, int offset) {
        return Math.floorMod((int) Math.floorDiv(rawSeconds + offset, 60L), MINUTES_PER_DAY);
    }

    /** The signed circular minute error (predicted - official) in {@code [-720, 719]}. */
    public static int circularMinuteError(int predictedMinute, int officialMinute) {
        int diff = predictedMinute - officialMinute;
        return Math.floorMod(diff + HALF_DAY, MINUTES_PER_DAY) - HALF_DAY;
    }

    /**
     * Evaluates one candidate offset over the given observations.
     *
     * @param offset       the candidate offset in seconds
     * @param observations the (city, prayer) observations
     * @return the metrics (never null; zeroed if observations is empty)
     */
    public static SecApproachMetrics evaluate(int offset, List<TrainingObservation> observations) {
        int exact = 0;
        int withinOne = 0;
        long sumAbs = 0;
        int maxAbs = 0;
        long signed = 0;
        TreeMap<Integer, Integer> histogram = new TreeMap<>();
        for (TrainingObservation o : observations) {
            int error = circularMinuteError(predictedMinute(o.rawSeconds(), offset), o.officialMinute());
            int abs = Math.abs(error);
            if (error == 0) {
                exact++;
            }
            if (abs <= 1) {
                withinOne++;
            }
            sumAbs += abs;
            maxAbs = Math.max(maxAbs, abs);
            signed += error;
            histogram.merge(error, 1, Integer::sum);
        }
        return new SecApproachMetrics(
                offset, observations.size(), exact, withinOne, sumAbs, maxAbs, signed, histogram);
    }
}
