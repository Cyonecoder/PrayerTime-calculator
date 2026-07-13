package io.github.cyonecoder.prayertime.audit.secapproach;

import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachChronologicalSplit.Partition;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachTrainingDataset.TrainingObservation;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.util.ArrayList;
import java.util.List;

/**
 * Honest, overfitting-aware evaluation for one city: for each prayer it fits the
 * offset on the <em>training</em> segment only, breaks ties on <em>validation</em>,
 * and evaluates the selected offset — untouched — on the <em>holdout</em>. It also
 * records the in-sample <em>release</em> offset (fit on all dates, the value that
 * ships) and its in-sample metrics, clearly separated from the out-of-sample
 * holdout estimate.
 *
 * <p>All comparisons are integer, so the selection is deterministic.
 */
public final class SecApproachSplitEvaluator {

    /** The split evaluation of one prayer. */
    public record PrayerSplitEvaluation(
            Prayer prayer,
            int splitOffset,
            SecApproachMetrics training,
            SecApproachMetrics validation,
            SecApproachMetrics holdout,
            int releaseOffset,
            SecApproachMetrics inSample) {
    }

    /** The split evaluation of one city (six prayers). */
    public record CitySplitEvaluation(String city, List<PrayerSplitEvaluation> prayers) {

        public CitySplitEvaluation {
            prayers = List.copyOf(prayers);
        }

        public double trainingExactPercentage() {
            return exactPct(PrayerSplitEvaluation::training);
        }

        public double validationExactPercentage() {
            return exactPct(PrayerSplitEvaluation::validation);
        }

        /** @return the honest, out-of-sample holdout exact-minute percentage. */
        public double holdoutExactPercentage() {
            return exactPct(PrayerSplitEvaluation::holdout);
        }

        /** @return the in-sample (all-dates) exact-minute percentage of the shipped offset. */
        public double inSampleExactPercentage() {
            return exactPct(PrayerSplitEvaluation::inSample);
        }

        private double exactPct(java.util.function.Function<PrayerSplitEvaluation, SecApproachMetrics> f) {
            int exact = 0;
            int n = 0;
            for (PrayerSplitEvaluation p : prayers) {
                exact += f.apply(p).exactCount();
                n += f.apply(p).observations();
            }
            return n == 0 ? 0.0 : 100.0 * exact / n;
        }
    }

    private final int low;
    private final int high;

    public SecApproachSplitEvaluator(int low, int high) {
        if (low > high) {
            throw new IllegalArgumentException("low > high");
        }
        this.low = low;
        this.high = high;
    }

    /** Evaluates the chronological split for every prayer of one city. */
    public CitySplitEvaluation evaluate(SecApproachTrainingDataset dataset, String city) {
        List<PrayerSplitEvaluation> prayers = new ArrayList<>();
        for (Prayer prayer : Prayer.values()) {
            List<TrainingObservation> all = dataset.observations(city, prayer);
            Partition part = SecApproachChronologicalSplit.partition(all);
            int splitOffset = selectOffset(part.training(), part.validation());
            int releaseOffset = SecApproachOffsetSearch.search(all, low, high).best().offset();
            prayers.add(new PrayerSplitEvaluation(
                    prayer,
                    splitOffset,
                    SecApproachEvaluator.evaluate(splitOffset, part.training()),
                    SecApproachEvaluator.evaluate(splitOffset, part.validation()),
                    SecApproachEvaluator.evaluate(splitOffset, part.holdout()),
                    releaseOffset,
                    SecApproachEvaluator.evaluate(releaseOffset, all)));
        }
        return new CitySplitEvaluation(city, prayers);
    }

    /**
     * Selects the offset that is best on training, ties broken by validation, then
     * by the deterministic |offset| / signed-offset rule.
     */
    private int selectOffset(List<TrainingObservation> training, List<TrainingObservation> validation) {
        int bestOffset = low;
        SecApproachMetrics bestTrain = null;
        SecApproachMetrics bestVal = null;
        for (int offset = low; offset <= high; offset++) {
            SecApproachMetrics train = SecApproachEvaluator.evaluate(offset, training);
            SecApproachMetrics val = SecApproachEvaluator.evaluate(offset, validation);
            if (bestTrain == null || better(train, val, offset, bestTrain, bestVal, bestOffset)) {
                bestTrain = train;
                bestVal = val;
                bestOffset = offset;
            }
        }
        return bestOffset;
    }

    /** @return true if candidate (train, val, offset) ranks strictly before the incumbent. */
    private static boolean better(
            SecApproachMetrics t, SecApproachMetrics v, int offset,
            SecApproachMetrics bt, SecApproachMetrics bv, int bestOffset) {
        int c = stage(t, bt);
        if (c != 0) {
            return c < 0;
        }
        c = stage(v, bv);
        if (c != 0) {
            return c < 0;
        }
        if (Math.abs(offset) != Math.abs(bestOffset)) {
            return Math.abs(offset) < Math.abs(bestOffset);
        }
        return offset < bestOffset;
    }

    /** Orders two metrics on one segment: exact desc, within-1 desc, sumAbs asc, maxAbs asc. */
    private static int stage(SecApproachMetrics a, SecApproachMetrics b) {
        if (a.exactCount() != b.exactCount()) {
            return Integer.compare(b.exactCount(), a.exactCount());
        }
        if (a.withinOneCount() != b.withinOneCount()) {
            return Integer.compare(b.withinOneCount(), a.withinOneCount());
        }
        if (a.sumAbsoluteError() != b.sumAbsoluteError()) {
            return Long.compare(a.sumAbsoluteError(), b.sumAbsoluteError());
        }
        return Integer.compare(a.maxAbsoluteError(), b.maxAbsoluteError());
    }
}
