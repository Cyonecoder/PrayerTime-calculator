package io.github.cyonecoder.prayertime.audit.optimization;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How a raw prayer time (in decimal minutes) is reduced to a published whole
 * minute. Research knob: the official Habous publication rule is unknown, so the
 * search tries every plausible policy.
 *
 * <p>Each policy rounds a <em>minutes</em> value to a whole number of minutes.
 * The five {@link RoundingMode}-backed policies are unambiguous; {@link
 * #NEAREST_MINUTE} is the conventional "round to the nearest minute" rule
 * implemented with {@link Math#round(double)} semantics (ties break toward
 * positive infinity), which differs from {@link #HALF_UP} (ties break away from
 * zero) only for negative inputs.
 */
public enum RoundingPolicy {

    /** Round toward negative infinity (always down). */
    FLOOR(RoundingMode.FLOOR),
    /** Round toward positive infinity (always up). */
    CEILING(RoundingMode.CEILING),
    /** Drop the fractional minute (round toward zero). */
    TRUNCATE(RoundingMode.DOWN),
    /** Nearest minute; ties break toward positive infinity (Math.round). */
    NEAREST_MINUTE(null),
    /** Nearest minute; ties break away from zero. */
    HALF_UP(RoundingMode.HALF_UP),
    /** Nearest minute; ties break to the even minute (banker's rounding). */
    HALF_EVEN(RoundingMode.HALF_EVEN);

    private final RoundingMode mode;

    RoundingPolicy(RoundingMode mode) {
        this.mode = mode;
    }

    /**
     * Rounds a decimal-minutes value to a whole number of minutes under this
     * policy.
     *
     * @param minutes the value in minutes (may be negative)
     * @return the rounded whole minutes
     * @throws IllegalArgumentException if {@code minutes} is non-finite
     */
    public long roundToWholeMinutes(double minutes) {
        if (Double.isNaN(minutes) || Double.isInfinite(minutes)) {
            throw new IllegalArgumentException("minutes must be finite but was " + minutes);
        }
        if (this == NEAREST_MINUTE) {
            return Math.round(minutes);
        }
        return BigDecimal.valueOf(minutes).setScale(0, mode).longValueExact();
    }
}
