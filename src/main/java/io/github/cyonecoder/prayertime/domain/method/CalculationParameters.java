package io.github.cyonecoder.prayertime.domain.method;

import java.util.Objects;

/**
 * Immutable astronomical parameters that configure a calculation method.
 *
 * <p>All angles are in degrees. Twilight angles ({@code fajrAngle},
 * {@code ishaAngle}) describe how far the sun is below the horizon; they must
 * be strictly between 0 and 90. The {@code asrShadowFactor} selects the Asr
 * school (1 for the majority/Shafi'i, 2 for Hanafi) and must be positive. The
 * {@code sunriseAngle} is the base horizon dip for sunrise/sunset (typically
 * about -0.8333°) before any elevation correction.
 *
 * @param fajrAngle       sun depression angle for Fajr, in (0, 90)
 * @param ishaAngle       sun depression angle for Isha, in (0, 90)
 * @param asrShadowFactor Asr shadow-length factor, &gt; 0
 * @param sunriseAngle    base sunrise/sunset horizon angle in degrees,
 *                        in [-90, 90]
 * @param adjustments     per-prayer minute corrections (method-level config)
 * @param elevationPolicy how observer elevation is sourced
 */
public record CalculationParameters(
        double fajrAngle,
        double ishaAngle,
        double asrShadowFactor,
        double sunriseAngle,
        PrayerAdjustments adjustments,
        ElevationPolicy elevationPolicy) {

    private static final double MIN_TWILIGHT_ANGLE = 0.0;
    private static final double MAX_TWILIGHT_ANGLE = 90.0;
    private static final double MIN_HORIZON_ANGLE = -90.0;
    private static final double MAX_HORIZON_ANGLE = 90.0;

    /**
     * Validates the parameters.
     *
     * @throws NullPointerException     if {@code adjustments} or
     *                                  {@code elevationPolicy} is {@code null}
     * @throws IllegalArgumentException if any angle is non-finite or out of
     *                                  range, or the shadow factor is not
     *                                  positive
     */
    public CalculationParameters {
        Objects.requireNonNull(adjustments, "adjustments");
        Objects.requireNonNull(elevationPolicy, "elevationPolicy");
        requireTwilightAngle(fajrAngle, "fajrAngle");
        requireTwilightAngle(ishaAngle, "ishaAngle");
        requirePositive(asrShadowFactor, "asrShadowFactor");
        requireInRange(sunriseAngle, MIN_HORIZON_ANGLE, MAX_HORIZON_ANGLE, "sunriseAngle");
    }

    private static void requireTwilightAngle(double angle, String name) {
        requireFinite(angle, name);
        if (angle <= MIN_TWILIGHT_ANGLE || angle >= MAX_TWILIGHT_ANGLE) {
            throw new IllegalArgumentException(
                    name + " must be within (0, 90) degrees but was " + angle);
        }
    }

    private static void requirePositive(double value, String name) {
        requireFinite(value, name);
        if (value <= 0.0) {
            throw new IllegalArgumentException(name + " must be positive but was " + value);
        }
    }

    private static void requireInRange(double value, double min, double max, String name) {
        requireFinite(value, name);
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    name + " must be within [" + min + ", " + max + "] but was " + value);
        }
    }

    private static void requireFinite(double value, String name) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be a finite number but was " + value);
        }
    }
}
