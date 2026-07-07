package io.github.cyonecoder.prayertime.audit.optimization;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * One fully-specified candidate parameter set for reproducing the Habous
 * schedule, plus its publication rounding policy and operation order.
 *
 * <p>Immutable. Every knob has a value; the {@link #tuned()} set records which
 * knobs are treated as free parameters in the search that produced this
 * candidate, and drives {@link #parameterCount()} (used as a parsimony
 * tie-breaker when ranking). Two candidates with identical values but different
 * {@code tuned} sets are considered different models.
 *
 * <p>This type never mutates production configuration. It is only read by the
 * research engine to build a raw calculation and compare it to reference times.
 */
public final class CalculationCandidate {

    /** The independently tunable knobs of a candidate. */
    public enum Knob {
        FAJR_ANGLE,
        ISHA_ANGLE,
        ASR_SHADOW_FACTOR,
        SUNRISE_ALTITUDE,
        MAGHRIB_ALTITUDE,
        FAJR_ADJUSTMENT,
        SUNRISE_ADJUSTMENT,
        DHUHR_ADJUSTMENT,
        ASR_ADJUSTMENT,
        MAGHRIB_ADJUSTMENT,
        ISHA_ADJUSTMENT,
        SUNRISE_DIP_COEFFICIENT,
        MAGHRIB_DIP_COEFFICIENT,
        ROUNDING_POLICY,
        OPERATION_ORDER
    }

    /** Default twilight/altitude/adjustment values (a neutral, un-buffered baseline). */
    public static final double DEFAULT_FAJR_ANGLE = 19.0;
    public static final double DEFAULT_ISHA_ANGLE = 17.0;
    public static final double DEFAULT_ASR_SHADOW_FACTOR = 1.0;
    public static final double DEFAULT_SUNRISE_ALTITUDE = -0.8333;
    public static final double DEFAULT_MAGHRIB_ALTITUDE = -0.8333;

    private final double fajrAngle;
    private final double ishaAngle;
    private final double asrShadowFactor;
    private final double sunriseAltitudeDegrees;
    private final double maghribAltitudeDegrees;
    private final int fajrAdjustmentSeconds;
    private final int sunriseAdjustmentSeconds;
    private final int dhuhrAdjustmentSeconds;
    private final int asrAdjustmentSeconds;
    private final int maghribAdjustmentSeconds;
    private final int ishaAdjustmentSeconds;
    private final double sunriseHorizonDipCoefficient;
    private final double maghribHorizonDipCoefficient;
    private final RoundingPolicy roundingPolicy;
    private final OperationOrder operationOrder;
    private final boolean elevationAdjustedHorizon;
    private final Set<Knob> tuned;

    private CalculationCandidate(Builder b) {
        this.fajrAngle = b.fajrAngle;
        this.ishaAngle = b.ishaAngle;
        this.asrShadowFactor = b.asrShadowFactor;
        this.sunriseAltitudeDegrees = b.sunriseAltitudeDegrees;
        this.maghribAltitudeDegrees = b.maghribAltitudeDegrees;
        this.fajrAdjustmentSeconds = b.fajrAdjustmentSeconds;
        this.sunriseAdjustmentSeconds = b.sunriseAdjustmentSeconds;
        this.dhuhrAdjustmentSeconds = b.dhuhrAdjustmentSeconds;
        this.asrAdjustmentSeconds = b.asrAdjustmentSeconds;
        this.maghribAdjustmentSeconds = b.maghribAdjustmentSeconds;
        this.ishaAdjustmentSeconds = b.ishaAdjustmentSeconds;
        this.sunriseHorizonDipCoefficient = b.sunriseHorizonDipCoefficient;
        this.maghribHorizonDipCoefficient = b.maghribHorizonDipCoefficient;
        this.roundingPolicy = b.roundingPolicy;
        this.operationOrder = b.operationOrder;
        this.elevationAdjustedHorizon = b.elevationAdjustedHorizon;
        this.tuned = b.tuned.isEmpty()
                ? EnumSet.noneOf(Knob.class)
                : EnumSet.copyOf(b.tuned);
    }

    /** @return a builder pre-loaded with the neutral baseline values. */
    public static Builder builder() {
        return new Builder();
    }

    /** @return a builder copy of this candidate (same values and tuned set). */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.fajrAngle = fajrAngle;
        b.ishaAngle = ishaAngle;
        b.asrShadowFactor = asrShadowFactor;
        b.sunriseAltitudeDegrees = sunriseAltitudeDegrees;
        b.maghribAltitudeDegrees = maghribAltitudeDegrees;
        b.fajrAdjustmentSeconds = fajrAdjustmentSeconds;
        b.sunriseAdjustmentSeconds = sunriseAdjustmentSeconds;
        b.dhuhrAdjustmentSeconds = dhuhrAdjustmentSeconds;
        b.asrAdjustmentSeconds = asrAdjustmentSeconds;
        b.maghribAdjustmentSeconds = maghribAdjustmentSeconds;
        b.ishaAdjustmentSeconds = ishaAdjustmentSeconds;
        b.sunriseHorizonDipCoefficient = sunriseHorizonDipCoefficient;
        b.maghribHorizonDipCoefficient = maghribHorizonDipCoefficient;
        b.roundingPolicy = roundingPolicy;
        b.operationOrder = operationOrder;
        b.elevationAdjustedHorizon = elevationAdjustedHorizon;
        b.tuned.addAll(tuned);
        return b;
    }

    public double fajrAngle() {
        return fajrAngle;
    }

    public double ishaAngle() {
        return ishaAngle;
    }

    public double asrShadowFactor() {
        return asrShadowFactor;
    }

    public double sunriseAltitudeDegrees() {
        return sunriseAltitudeDegrees;
    }

    public double maghribAltitudeDegrees() {
        return maghribAltitudeDegrees;
    }

    public int fajrAdjustmentSeconds() {
        return fajrAdjustmentSeconds;
    }

    public int sunriseAdjustmentSeconds() {
        return sunriseAdjustmentSeconds;
    }

    public int dhuhrAdjustmentSeconds() {
        return dhuhrAdjustmentSeconds;
    }

    public int asrAdjustmentSeconds() {
        return asrAdjustmentSeconds;
    }

    public int maghribAdjustmentSeconds() {
        return maghribAdjustmentSeconds;
    }

    public int ishaAdjustmentSeconds() {
        return ishaAdjustmentSeconds;
    }

    /**
     * The fitted horizon-dip coefficient for sunrise, in degrees per √metre. The
     * effective sunrise altitude is {@code sunriseAltitudeDegrees - coefficient·√elevation},
     * an empirically fitted effective horizon (not a physical constant). Zero
     * means no dip. Applied in preference to the fixed {@link #elevationAdjustedHorizon()}
     * family when non-zero.
     *
     * @return the sunrise dip coefficient (deg/√m), {@code >= 0}
     */
    public double sunriseHorizonDipCoefficient() {
        return sunriseHorizonDipCoefficient;
    }

    /**
     * The fitted horizon-dip coefficient for Maghrib, in degrees per √metre.
     * See {@link #sunriseHorizonDipCoefficient()}; Maghrib is fitted independently
     * because the observed elevation sensitivity of sunset differs from sunrise.
     *
     * @return the Maghrib dip coefficient (deg/√m), {@code >= 0}
     */
    public double maghribHorizonDipCoefficient() {
        return maghribHorizonDipCoefficient;
    }

    public RoundingPolicy roundingPolicy() {
        return roundingPolicy;
    }

    public OperationOrder operationOrder() {
        return operationOrder;
    }

    /**
     * Whether the sunrise/Maghrib horizon altitude is corrected for the
     * observer's elevation via a global dip function (no per-city constant).
     *
     * @return {@code true} if the elevation dip is applied to sunrise/Maghrib
     */
    public boolean elevationAdjustedHorizon() {
        return elevationAdjustedHorizon;
    }

    /** @return an immutable view of the tuned knobs. */
    public Set<Knob> tuned() {
        return java.util.Collections.unmodifiableSet(tuned);
    }

    /** @return the number of free parameters in this model (parsimony metric). */
    public int parameterCount() {
        return tuned.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CalculationCandidate other)) {
            return false;
        }
        return Double.compare(fajrAngle, other.fajrAngle) == 0
                && Double.compare(ishaAngle, other.ishaAngle) == 0
                && Double.compare(asrShadowFactor, other.asrShadowFactor) == 0
                && Double.compare(sunriseAltitudeDegrees, other.sunriseAltitudeDegrees) == 0
                && Double.compare(maghribAltitudeDegrees, other.maghribAltitudeDegrees) == 0
                && fajrAdjustmentSeconds == other.fajrAdjustmentSeconds
                && sunriseAdjustmentSeconds == other.sunriseAdjustmentSeconds
                && dhuhrAdjustmentSeconds == other.dhuhrAdjustmentSeconds
                && asrAdjustmentSeconds == other.asrAdjustmentSeconds
                && maghribAdjustmentSeconds == other.maghribAdjustmentSeconds
                && ishaAdjustmentSeconds == other.ishaAdjustmentSeconds
                && Double.compare(sunriseHorizonDipCoefficient, other.sunriseHorizonDipCoefficient) == 0
                && Double.compare(maghribHorizonDipCoefficient, other.maghribHorizonDipCoefficient) == 0
                && roundingPolicy == other.roundingPolicy
                && operationOrder == other.operationOrder
                && elevationAdjustedHorizon == other.elevationAdjustedHorizon
                && tuned.equals(other.tuned);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fajrAngle, ishaAngle, asrShadowFactor, sunriseAltitudeDegrees,
                maghribAltitudeDegrees, fajrAdjustmentSeconds, sunriseAdjustmentSeconds,
                dhuhrAdjustmentSeconds, asrAdjustmentSeconds, maghribAdjustmentSeconds,
                ishaAdjustmentSeconds, sunriseHorizonDipCoefficient, maghribHorizonDipCoefficient,
                roundingPolicy, operationOrder, elevationAdjustedHorizon, tuned);
    }

    @Override
    public String toString() {
        return "CalculationCandidate{"
                + "fajrAngle=" + fajrAngle
                + ", ishaAngle=" + ishaAngle
                + ", asrShadowFactor=" + asrShadowFactor
                + ", sunriseAltitude=" + sunriseAltitudeDegrees
                + ", maghribAltitude=" + maghribAltitudeDegrees
                + ", sunriseDip=" + sunriseHorizonDipCoefficient
                + ", maghribDip=" + maghribHorizonDipCoefficient
                + ", roundingPolicy=" + roundingPolicy
                + ", operationOrder=" + operationOrder
                + ", elevationAdjustedHorizon=" + elevationAdjustedHorizon
                + ", parameterCount=" + parameterCount()
                + '}';
    }

    /** Mutable builder for {@link CalculationCandidate}. */
    public static final class Builder {
        private double fajrAngle = DEFAULT_FAJR_ANGLE;
        private double ishaAngle = DEFAULT_ISHA_ANGLE;
        private double asrShadowFactor = DEFAULT_ASR_SHADOW_FACTOR;
        private double sunriseAltitudeDegrees = DEFAULT_SUNRISE_ALTITUDE;
        private double maghribAltitudeDegrees = DEFAULT_MAGHRIB_ALTITUDE;
        private int fajrAdjustmentSeconds;
        private int sunriseAdjustmentSeconds;
        private int dhuhrAdjustmentSeconds;
        private int asrAdjustmentSeconds;
        private int maghribAdjustmentSeconds;
        private int ishaAdjustmentSeconds;
        private double sunriseHorizonDipCoefficient;
        private double maghribHorizonDipCoefficient;
        private RoundingPolicy roundingPolicy = RoundingPolicy.NEAREST_MINUTE;
        private OperationOrder operationOrder = OperationOrder.RAW_THEN_ADJUST_THEN_ROUND;
        private boolean elevationAdjustedHorizon;
        private final EnumSet<Knob> tuned = EnumSet.noneOf(Knob.class);

        private Builder() {
        }

        public Builder fajrAngle(double v) {
            this.fajrAngle = v;
            tuned.add(Knob.FAJR_ANGLE);
            return this;
        }

        public Builder ishaAngle(double v) {
            this.ishaAngle = v;
            tuned.add(Knob.ISHA_ANGLE);
            return this;
        }

        public Builder asrShadowFactor(double v) {
            this.asrShadowFactor = v;
            tuned.add(Knob.ASR_SHADOW_FACTOR);
            return this;
        }

        public Builder sunriseAltitudeDegrees(double v) {
            this.sunriseAltitudeDegrees = v;
            tuned.add(Knob.SUNRISE_ALTITUDE);
            return this;
        }

        public Builder maghribAltitudeDegrees(double v) {
            this.maghribAltitudeDegrees = v;
            tuned.add(Knob.MAGHRIB_ALTITUDE);
            return this;
        }

        public Builder fajrAdjustmentSeconds(int v) {
            this.fajrAdjustmentSeconds = v;
            tuned.add(Knob.FAJR_ADJUSTMENT);
            return this;
        }

        public Builder sunriseAdjustmentSeconds(int v) {
            this.sunriseAdjustmentSeconds = v;
            tuned.add(Knob.SUNRISE_ADJUSTMENT);
            return this;
        }

        public Builder dhuhrAdjustmentSeconds(int v) {
            this.dhuhrAdjustmentSeconds = v;
            tuned.add(Knob.DHUHR_ADJUSTMENT);
            return this;
        }

        public Builder asrAdjustmentSeconds(int v) {
            this.asrAdjustmentSeconds = v;
            tuned.add(Knob.ASR_ADJUSTMENT);
            return this;
        }

        public Builder maghribAdjustmentSeconds(int v) {
            this.maghribAdjustmentSeconds = v;
            tuned.add(Knob.MAGHRIB_ADJUSTMENT);
            return this;
        }

        public Builder ishaAdjustmentSeconds(int v) {
            this.ishaAdjustmentSeconds = v;
            tuned.add(Knob.ISHA_ADJUSTMENT);
            return this;
        }

        /**
         * Sets the fitted sunrise horizon-dip coefficient (deg/√m) and counts it
         * as a tuned parameter.
         *
         * @param v the coefficient, {@code >= 0}
         * @return this builder
         */
        public Builder sunriseHorizonDipCoefficient(double v) {
            this.sunriseHorizonDipCoefficient = v;
            tuned.add(Knob.SUNRISE_DIP_COEFFICIENT);
            return this;
        }

        /**
         * Sets the fitted Maghrib horizon-dip coefficient (deg/√m) and counts it
         * as a tuned parameter.
         *
         * @param v the coefficient, {@code >= 0}
         * @return this builder
         */
        public Builder maghribHorizonDipCoefficient(double v) {
            this.maghribHorizonDipCoefficient = v;
            tuned.add(Knob.MAGHRIB_DIP_COEFFICIENT);
            return this;
        }

        public Builder roundingPolicy(RoundingPolicy policy) {
            this.roundingPolicy = Objects.requireNonNull(policy, "policy");
            tuned.add(Knob.ROUNDING_POLICY);
            return this;
        }

        /** Sets the rounding policy without counting it as a tuned parameter. */
        public Builder roundingPolicyFixed(RoundingPolicy policy) {
            this.roundingPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        public Builder operationOrder(OperationOrder order) {
            this.operationOrder = Objects.requireNonNull(order, "order");
            tuned.add(Knob.OPERATION_ORDER);
            return this;
        }

        /** Sets the operation order without counting it as a tuned parameter. */
        public Builder operationOrderFixed(OperationOrder order) {
            this.operationOrder = Objects.requireNonNull(order, "order");
            return this;
        }

        /**
         * Enables or disables the global elevation-dip correction on the
         * sunrise/Maghrib horizon altitude. This is a structural choice of the
         * model family, not a fitted parameter, so it is not added to the tuned
         * set and does not affect {@link #parameterCount()}.
         *
         * @param enabled whether to apply the elevation dip
         * @return this builder
         */
        public Builder elevationAdjustedHorizon(boolean enabled) {
            this.elevationAdjustedHorizon = enabled;
            return this;
        }

        public CalculationCandidate build() {
            return new CalculationCandidate(this);
        }
    }
}
