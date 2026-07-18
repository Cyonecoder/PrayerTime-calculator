package io.github.cyonecoder.prayertime.audit.secapproach;

import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachTrainingDataset.TrainingObservation;
import java.util.List;

/**
 * Deterministic discrete search for the best integer-second offset of one
 * (city, prayer), by exhaustively evaluating every offset in a bounded window and
 * ranking with {@link SecApproachMetrics#BEST_FIRST}.
 *
 * <p>The default window is {@code [-900, +900]} seconds — comfortably wider than
 * the current offset scale, yet narrow enough that a many-minute "offset" (the
 * signature of a wrong city or wrong coordinate) cannot be silently accepted. The
 * window is only widened deliberately (see {@link Result#touchesBoundary()}),
 * never automatically.
 *
 * <p>The search is exhaustive and order-independent: the full ordering is decided
 * by the comparator, so the same observations always yield the same offset.
 */
public final class SecApproachOffsetSearch {

    /** Default inclusive lower bound of the offset search, in seconds. */
    public static final int DEFAULT_LOW = -900;
    /** Default inclusive upper bound of the offset search, in seconds. */
    public static final int DEFAULT_HIGH = 900;

    private SecApproachOffsetSearch() {
    }

    /**
     * The search outcome for one (city, prayer).
     *
     * @param best  the winning candidate's metrics
     * @param low   inclusive lower search bound
     * @param high  inclusive upper search bound
     */
    public record Result(SecApproachMetrics best, int low, int high) {

        /**
         * @return whether the winning offset sits on a search boundary — a signal to
         *     recheck coordinates/timezone/mapping and, only then and documented,
         *     widen the window; never a reason to silently accept it.
         */
        public boolean touchesBoundary() {
            return best.touchesBoundary(low, high);
        }
    }

    /** Searches the default {@code [-900, +900]} window. */
    public static Result search(List<TrainingObservation> observations) {
        return search(observations, DEFAULT_LOW, DEFAULT_HIGH);
    }

    /**
     * Searches {@code [low, high]} inclusive.
     *
     * @param observations the (city, prayer) observations (must be non-empty)
     * @param low          inclusive lower bound (seconds)
     * @param high         inclusive upper bound (seconds)
     * @return the winning offset's metrics and the window used
     */
    public static Result search(List<TrainingObservation> observations, int low, int high) {
        validate(observations);
        if (low > high) {
            throw new IllegalArgumentException("low (" + low + ") > high (" + high + ")");
        }
        SecApproachMetrics best = null;
        for (int offset = low; offset <= high; offset++) {
            SecApproachMetrics candidate = SecApproachEvaluator.evaluate(offset, observations);
            if (best == null || SecApproachMetrics.BEST_FIRST.compare(candidate, best) < 0) {
                best = candidate;
            }
        }
        return new Result(best, low, high);
    }

    /**
     * Rejects incoherent input: one (city, prayer)'s observations must be non-empty,
     * all the same city and prayer, and have distinct dates. This guards against
     * mixed-city, wrong-prayer, incomplete, or duplicated data being fitted.
     *
     * @param observations the observations to validate
     * @throws IllegalArgumentException if the observations are incoherent
     */
    public static void validate(List<TrainingObservation> observations) {
        if (observations == null || observations.isEmpty()) {
            throw new IllegalArgumentException("no observations to search (incomplete data)");
        }
        TrainingObservation first = observations.get(0);
        java.util.Set<java.time.LocalDate> dates = new java.util.HashSet<>();
        for (TrainingObservation o : observations) {
            if (!o.city().equals(first.city())) {
                throw new IllegalArgumentException("mixed-city observations: "
                        + first.city() + " and " + o.city());
            }
            if (o.prayer() != first.prayer()) {
                throw new IllegalArgumentException("mixed-prayer observations: "
                        + first.prayer() + " and " + o.prayer());
            }
            if (!dates.add(o.date())) {
                throw new IllegalArgumentException("duplicate observation for date " + o.date());
            }
        }
    }
}
