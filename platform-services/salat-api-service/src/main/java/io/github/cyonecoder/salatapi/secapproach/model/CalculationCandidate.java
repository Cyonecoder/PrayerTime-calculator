package io.github.cyonecoder.salatapi.secapproach.model;

import java.util.Objects;

/**
 * One fully-specified parameter set for reproducing the Habous schedule, plus its
 * publication rounding policy and operation order. Immutable.
 *
 * <p>Ported from {@code audit.optimization.CalculationCandidate}, reduced to the
 * sixteen fields that affect a published time. The original carried a builder and
 * a {@code tuned} set of {@link RoundingPolicy}-style knobs used to rank
 * candidates during the parameter search by parsimony; the search is excluded
 * from this service (spec 9), so that machinery is deliberately not ported. This
 * service consumes released parameters — it never trains them. See
 * {@code docs/EXTRACTION_MAP.md}.
 *
 * @param fajrAngle                    twilight depression angle for Fajr, degrees
 * @param ishaAngle                    twilight depression angle for Isha, degrees
 * @param asrShadowFactor              Asr shadow-length factor (1 majority, 2 Hanafi)
 * @param sunriseAltitudeDegrees       sun altitude at sunrise, degrees
 * @param maghribAltitudeDegrees       sun altitude at Maghrib, degrees
 * @param fajrAdjustmentSeconds        whole-second adjustment applied to Fajr
 * @param sunriseAdjustmentSeconds     whole-second adjustment applied to sunrise
 * @param dhuhrAdjustmentSeconds       whole-second adjustment applied to Dhuhr
 * @param asrAdjustmentSeconds         whole-second adjustment applied to Asr
 * @param maghribAdjustmentSeconds     whole-second adjustment applied to Maghrib
 * @param ishaAdjustmentSeconds        whole-second adjustment applied to Isha
 * @param sunriseHorizonDipCoefficient fitted dip coefficient (deg/√m) for sunrise
 * @param maghribHorizonDipCoefficient fitted dip coefficient (deg/√m) for Maghrib
 * @param roundingPolicy               how a decimal minute becomes a published minute
 * @param operationOrder               the order of raw/adjust/round
 * @param elevationAdjustedHorizon     whether the standard PrayTimes dip applies
 */
public record CalculationCandidate(
        double fajrAngle,
        double ishaAngle,
        double asrShadowFactor,
        double sunriseAltitudeDegrees,
        double maghribAltitudeDegrees,
        int fajrAdjustmentSeconds,
        int sunriseAdjustmentSeconds,
        int dhuhrAdjustmentSeconds,
        int asrAdjustmentSeconds,
        int maghribAdjustmentSeconds,
        int ishaAdjustmentSeconds,
        double sunriseHorizonDipCoefficient,
        double maghribHorizonDipCoefficient,
        RoundingPolicy roundingPolicy,
        OperationOrder operationOrder,
        boolean elevationAdjustedHorizon) {

    public CalculationCandidate {
        Objects.requireNonNull(roundingPolicy, "roundingPolicy");
        Objects.requireNonNull(operationOrder, "operationOrder");
        if (asrShadowFactor <= 0.0) {
            throw new IllegalArgumentException("asrShadowFactor must be > 0 but was " + asrShadowFactor);
        }
    }
}
