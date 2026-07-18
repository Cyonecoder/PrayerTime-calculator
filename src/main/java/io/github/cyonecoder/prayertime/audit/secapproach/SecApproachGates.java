package io.github.cyonecoder.prayertime.audit.secapproach;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The sec-approach release accuracy gates.
 *
 * <p><b>Per newly trained city</b> (all must hold to be presented as supported):
 * full-dataset exact ≥ 90%, out-of-sample holdout exact ≥ 85%, within ±1 minute
 * ≥ 98%, max absolute error ≤ 2 minutes. A city below any threshold is not
 * silently supported — it is marked {@code FAILED_ACCURACY_GATE}.
 *
 * <p><b>Aggregate</b> across all accepted cities: exact ≥ 95%, improvement over
 * the global reconstructed formula ≥ 10 percentage points, within ±1 minute
 * ≥ 99.5%, max absolute error ≤ 2 minutes.
 */
public final class SecApproachGates {

    /** Per-city minimum full-dataset (in-sample) exact-minute percentage. */
    public static final double MIN_FULL_EXACT_PCT = 90.0;
    /** Per-city minimum out-of-sample holdout exact-minute percentage. */
    public static final double MIN_HOLDOUT_EXACT_PCT = 85.0;
    /** Per-city minimum within-±1-minute percentage. */
    public static final double MIN_WITHIN_ONE_PCT = 98.0;
    /** Per-city maximum absolute error, minutes. */
    public static final int MAX_ABS_ERROR_MIN = 2;

    /** Aggregate minimum exact-minute percentage. */
    public static final double AGG_MIN_EXACT_PCT = 95.0;
    /** Aggregate minimum improvement over the global formula, percentage points. */
    public static final double AGG_MIN_IMPROVEMENT_PP = 10.0;
    /** Aggregate minimum within-±1-minute percentage. */
    public static final double AGG_MIN_WITHIN_ONE_PCT = 99.5;
    /** Aggregate maximum absolute error, minutes. */
    public static final int AGG_MAX_ABS_ERROR_MIN = 2;

    private static final double EPS = 1e-9;

    /** The outcome of a gate evaluation: whether it passed and, if not, why. */
    public record Result(boolean passed, List<String> failures) {
        public Result {
            failures = List.copyOf(failures);
        }
    }

    private SecApproachGates() {
    }

    /**
     * Evaluates the per-city gates for one newly trained city.
     *
     * @param fullExactPct     in-sample exact-minute percentage over all dates
     * @param holdoutExactPct  out-of-sample holdout exact-minute percentage
     * @param withinOnePct     within-±1-minute percentage (in-sample)
     * @param maxAbsoluteError maximum absolute error in minutes (in-sample)
     * @return the gate result
     */
    public static Result perCity(
            double fullExactPct, double holdoutExactPct, double withinOnePct, int maxAbsoluteError) {
        List<String> failures = new ArrayList<>();
        if (fullExactPct + EPS < MIN_FULL_EXACT_PCT) {
            failures.add(fail("full-exact", fullExactPct, MIN_FULL_EXACT_PCT));
        }
        if (holdoutExactPct + EPS < MIN_HOLDOUT_EXACT_PCT) {
            failures.add(fail("holdout-exact", holdoutExactPct, MIN_HOLDOUT_EXACT_PCT));
        }
        if (withinOnePct + EPS < MIN_WITHIN_ONE_PCT) {
            failures.add(fail("within-1min", withinOnePct, MIN_WITHIN_ONE_PCT));
        }
        if (maxAbsoluteError > MAX_ABS_ERROR_MIN) {
            failures.add("max-abs-error=" + maxAbsoluteError + "min > " + MAX_ABS_ERROR_MIN + "min");
        }
        return new Result(failures.isEmpty(), failures);
    }

    /**
     * Evaluates the aggregate gates across all accepted cities.
     *
     * @param exactPct       aggregate exact-minute percentage
     * @param improvementPp  improvement over the global formula, percentage points
     * @param withinOnePct   aggregate within-±1-minute percentage
     * @param maxAbsoluteError aggregate maximum absolute error, minutes
     * @return the gate result
     */
    public static Result aggregate(
            double exactPct, double improvementPp, double withinOnePct, int maxAbsoluteError) {
        List<String> failures = new ArrayList<>();
        if (exactPct + EPS < AGG_MIN_EXACT_PCT) {
            failures.add(fail("aggregate-exact", exactPct, AGG_MIN_EXACT_PCT));
        }
        if (improvementPp + EPS < AGG_MIN_IMPROVEMENT_PP) {
            failures.add(fail("improvement-pp", improvementPp, AGG_MIN_IMPROVEMENT_PP));
        }
        if (withinOnePct + EPS < AGG_MIN_WITHIN_ONE_PCT) {
            failures.add(fail("aggregate-within-1min", withinOnePct, AGG_MIN_WITHIN_ONE_PCT));
        }
        if (maxAbsoluteError > AGG_MAX_ABS_ERROR_MIN) {
            failures.add("aggregate-max-abs-error=" + maxAbsoluteError + "min > "
                    + AGG_MAX_ABS_ERROR_MIN + "min");
        }
        return new Result(failures.isEmpty(), failures);
    }

    private static String fail(String name, double actual, double threshold) {
        return String.format(Locale.ROOT, "%s=%.2f%% < %.2f%%", name, actual, threshold);
    }
}
