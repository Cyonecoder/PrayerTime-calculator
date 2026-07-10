package io.github.cyonecoder.prayertime.audit.optimization;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An inclusive numeric search range {@code [min, max]} traversed in fixed
 * {@code step} increments. Values are generated with {@link BigDecimal} so the
 * decimal step is exact (e.g. a 0.001 step never drifts to 17.000000000004).
 *
 * <p>The range is immutable. {@link #values()} returns the generated values in
 * ascending order as {@code double}s; {@link #decimalValues()} returns the exact
 * {@link BigDecimal} grid if callers need it.
 *
 * @param name        a short identifier for the searched parameter, e.g.
 *                    {@code "sunriseAltitudeDegrees"}
 * @param min         inclusive lower bound
 * @param max         inclusive upper bound (must be &ge; {@code min})
 * @param step        strictly positive increment
 */
public record ParameterRange(String name, BigDecimal min, BigDecimal max, BigDecimal step) {

    /** Guards against pathological ranges that would generate an unbounded grid. */
    private static final int MAX_STEPS = 20_000_000;

    public ParameterRange {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        Objects.requireNonNull(step, "step");
        if (step.signum() <= 0) {
            throw new IllegalArgumentException("step must be positive but was " + step);
        }
        if (max.compareTo(min) < 0) {
            throw new IllegalArgumentException("max (" + max + ") must be >= min (" + min + ")");
        }
    }

    /**
     * Builds a range from {@code double} bounds. The bounds and step are parsed
     * through {@link BigDecimal#valueOf(double)} so their canonical decimal form
     * is preserved (0.001 stays 0.001).
     *
     * @param name identifier for the parameter
     * @param min  inclusive lower bound
     * @param max  inclusive upper bound
     * @param step positive increment
     * @return the range
     */
    public static ParameterRange of(String name, double min, double max, double step) {
        return new ParameterRange(
                name, BigDecimal.valueOf(min), BigDecimal.valueOf(max), BigDecimal.valueOf(step));
    }

    /**
     * The number of grid points this range produces.
     *
     * @return the count of generated values (&ge; 1)
     */
    public int size() {
        // floor((max - min) / step) + 1, inclusive of both ends.
        BigDecimal spanSteps = max.subtract(min).divide(step, MathContext.DECIMAL64);
        long steps = spanSteps.setScale(0, java.math.RoundingMode.FLOOR).longValueExact();
        long count = steps + 1;
        if (count > MAX_STEPS) {
            throw new IllegalStateException(
                    "Range '" + name + "' would generate " + count
                            + " values which exceeds the cap of " + MAX_STEPS);
        }
        return (int) count;
    }

    /**
     * The exact grid as {@link BigDecimal}s, ascending, including both endpoints
     * (the last point never overshoots {@code max}).
     *
     * @return an immutable ascending list of values
     */
    public List<BigDecimal> decimalValues() {
        int count = size();
        List<BigDecimal> out = new ArrayList<>(count);
        BigDecimal value = min;
        for (int i = 0; i < count; i++) {
            out.add(value);
            value = value.add(step);
        }
        return List.copyOf(out);
    }

    /**
     * The grid as ascending {@code double}s.
     *
     * @return an immutable ascending list of values
     */
    public List<Double> values() {
        List<BigDecimal> decimals = decimalValues();
        List<Double> out = new ArrayList<>(decimals.size());
        for (BigDecimal d : decimals) {
            out.add(d.doubleValue());
        }
        return List.copyOf(out);
    }
}
