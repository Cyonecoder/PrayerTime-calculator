package io.github.cyonecoder.salatapi.secapproach.model;

/**
 * The order in which the three publication steps — the raw astronomical value,
 * the whole-minute adjustment and the rounding to a published minute — are
 * applied.
 *
 * <p>All variants operate on the raw event time expressed in seconds of the day
 * and produce a published minute of day. The adjustment is expressed in whole
 * seconds.
 *
 * <p>Ported verbatim from {@code audit.optimization.OperationOrder}; see
 * {@code docs/EXTRACTION_MAP.md}. The released v2 parameters select
 * {@link #RAW_THEN_ADJUST_THEN_ROUND}.
 */
public enum OperationOrder {

    /** Add the adjustment to the raw seconds, then round to a minute. */
    RAW_THEN_ADJUST_THEN_ROUND,

    /** Round the raw seconds to a minute, then add the adjustment. */
    RAW_THEN_ROUND_THEN_ADJUST,

    /**
     * Truncate the raw value to whole seconds (drop sub-second precision), then
     * add the adjustment, then round to a minute.
     */
    RAW_THEN_TRUNCATE_SECONDS_THEN_ADJUST_THEN_ROUND;

    private static final int SECONDS_PER_MINUTE = 60;
    private static final int MINUTES_PER_DAY = 1440;

    /**
     * @param rawSecondsOfDay   the raw event time in seconds of the day (may be
     *                          fractional, may lie outside [0, 86400))
     * @param adjustmentSeconds the whole-second adjustment to apply
     * @param policy            the rounding policy for the ROUND steps
     * @return the published minute of day, normalized to [0, 1439]
     */
    public int publishedMinuteOfDay(
            double rawSecondsOfDay, int adjustmentSeconds, RoundingPolicy policy) {
        long minuteOfDay = switch (this) {
            case RAW_THEN_ADJUST_THEN_ROUND ->
                    policy.roundToWholeMinutes((rawSecondsOfDay + adjustmentSeconds) / SECONDS_PER_MINUTE);
            case RAW_THEN_ROUND_THEN_ADJUST -> {
                long roundedMinute = policy.roundToWholeMinutes(rawSecondsOfDay / SECONDS_PER_MINUTE);
                double adjustedSeconds = (double) roundedMinute * SECONDS_PER_MINUTE + adjustmentSeconds;
                // Final publication is a whole minute: floor the adjusted seconds.
                yield Math.floorDiv((long) Math.floor(adjustedSeconds), SECONDS_PER_MINUTE);
            }
            case RAW_THEN_TRUNCATE_SECONDS_THEN_ADJUST_THEN_ROUND -> {
                double truncated = Math.floor(rawSecondsOfDay);
                yield policy.roundToWholeMinutes((truncated + adjustmentSeconds) / SECONDS_PER_MINUTE);
            }
        };
        return (int) Math.floorMod(minuteOfDay, (long) MINUTES_PER_DAY);
    }
}
