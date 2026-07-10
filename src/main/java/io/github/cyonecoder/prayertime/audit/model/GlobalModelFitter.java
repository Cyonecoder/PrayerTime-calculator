package io.github.cyonecoder.prayertime.audit.model;

import io.github.cyonecoder.prayertime.audit.optimization.CalculationCandidate;
import io.github.cyonecoder.prayertime.audit.optimization.CandidateResult;
import io.github.cyonecoder.prayertime.audit.optimization.CityLocations;
import io.github.cyonecoder.prayertime.audit.optimization.HabousRawEngine;
import io.github.cyonecoder.prayertime.audit.optimization.OperationOrder;
import io.github.cyonecoder.prayertime.audit.optimization.ParameterRange;
import io.github.cyonecoder.prayertime.audit.optimization.PrayerParameterSearch;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset;
import io.github.cyonecoder.prayertime.audit.optimization.RoundingPolicy;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;

/**
 * Fits a {@link GlobalModelFamily}'s free parameters against a training dataset.
 *
 * <p>The fit is a deterministic one-dimensional grid search per parameter, run in
 * the audit's prescribed order (Dhuhr, Asr, Fajr, Isha, Chourouk, Maghrib). Each
 * prayer's raw astronomical value depends only on that prayer's own knob, so the
 * parameters are separable and the searches never enumerate a Cartesian product.
 *
 * <p>The result is a single {@link CalculationCandidate} applied identically to
 * every city: the fitter introduces <strong>no</strong> city-specific
 * corrections. The rounding policy and operation order are held fixed at the
 * publication convention established by the Prompt-1/2 audit (nearest-minute,
 * raw→adjust→round).
 */
public final class GlobalModelFitter {

    /** Fixed publication convention inherited from the rounding-policy audit. */
    public static final RoundingPolicy ROUNDING = RoundingPolicy.NEAREST_MINUTE;
    /** Fixed operation order inherited from the rounding-policy audit. */
    public static final OperationOrder ORDER = OperationOrder.RAW_THEN_ADJUST_THEN_ROUND;

    /** Precaution-buffer grid, in seconds, for the with-precautions family. */
    private static final ParameterRange PRECAUTION_SECONDS =
            ParameterRange.of("precautionSeconds", 0, 600, 1);

    private final HabousRawEngine engine;
    private final CityLocations locations;
    private final PrayerParameterSearch.Ranges ranges;

    public GlobalModelFitter(HabousRawEngine engine, CityLocations locations) {
        this(engine, locations, PrayerParameterSearch.Ranges.defaults());
    }

    public GlobalModelFitter(
            HabousRawEngine engine, CityLocations locations, PrayerParameterSearch.Ranges ranges) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.locations = Objects.requireNonNull(locations, "locations");
        this.ranges = Objects.requireNonNull(ranges, "ranges");
    }

    /**
     * Fits the family against the training observations.
     *
     * @param family   the model family
     * @param training the training dataset (must not include validation/holdout)
     * @return the fitted global candidate
     */
    public CalculationCandidate fit(GlobalModelFamily family, ReferenceDataset training) {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(training, "training");
        if (!family.isFitted()) {
            return legacyCandidate();
        }

        Supplier<CalculationCandidate.Builder> structural = () -> {
            CalculationCandidate.Builder b = CalculationCandidate.builder()
                    .roundingPolicyFixed(ROUNDING)
                    .operationOrderFixed(ORDER);
            if (family.isElevationAdjusted()) {
                b.elevationAdjustedHorizon(true);
            }
            return b;
        };

        int dhuhrSeconds = fitInt(training, ranges.dhuhrAdjustment(), Prayer.DHUHR,
                v -> structural.get().dhuhrAdjustmentSeconds((int) Math.round(v)).build(),
                CalculationCandidate::dhuhrAdjustmentSeconds);
        double asrShadow = fitDouble(training, ranges.asrShadowFactor(), Prayer.ASR,
                v -> structural.get().asrShadowFactor(v).build(),
                CalculationCandidate::asrShadowFactor);
        double fajrAngle = fitDouble(training, ranges.fajrAngle(), Prayer.FAJR,
                v -> structural.get().fajrAngle(v).build(),
                CalculationCandidate::fajrAngle);
        double ishaAngle = fitDouble(training, ranges.ishaAngle(), Prayer.ISHA,
                v -> structural.get().ishaAngle(v).build(),
                CalculationCandidate::ishaAngle);

        CalculationCandidate.Builder result = structural.get()
                .dhuhrAdjustmentSeconds(dhuhrSeconds)
                .asrShadowFactor(asrShadow)
                .fajrAngle(fajrAngle)
                .ishaAngle(ishaAngle);

        fitHorizon(family, training, structural, result);
        return result.build();
    }

    /** @return the frozen production Morocco parameters as an audit candidate. */
    public CalculationCandidate legacyCandidate() {
        return CalculationCandidate.builder()
                .roundingPolicyFixed(ROUNDING)
                .operationOrderFixed(ORDER)
                .fajrAngle(CalculationCandidate.DEFAULT_FAJR_ANGLE)
                .ishaAngle(CalculationCandidate.DEFAULT_ISHA_ANGLE)
                .asrShadowFactor(CalculationCandidate.DEFAULT_ASR_SHADOW_FACTOR)
                .sunriseAltitudeDegrees(CalculationCandidate.DEFAULT_SUNRISE_ALTITUDE)
                .maghribAltitudeDegrees(CalculationCandidate.DEFAULT_MAGHRIB_ALTITUDE)
                .dhuhrAdjustmentSeconds(300)
                .maghribAdjustmentSeconds(300)
                .build();
    }

    // --- Horizon fitting per family ------------------------------------------

    private void fitHorizon(
            GlobalModelFamily family, ReferenceDataset training,
            Supplier<CalculationCandidate.Builder> structural, CalculationCandidate.Builder result) {
        switch (family.horizon()) {
            case STANDARD_WITH_PRECAUTION -> {
                double alt = CalculationCandidate.DEFAULT_SUNRISE_ALTITUDE;
                int sunriseBuffer = fitInt(training, PRECAUTION_SECONDS, Prayer.SUNRISE,
                        v -> structural.get().sunriseAltitudeDegrees(alt)
                                .sunriseAdjustmentSeconds((int) Math.round(v)).build(),
                        CalculationCandidate::sunriseAdjustmentSeconds);
                int maghribBuffer = fitInt(training, PRECAUTION_SECONDS, Prayer.MAGHRIB,
                        v -> structural.get().maghribAltitudeDegrees(alt)
                                .maghribAdjustmentSeconds((int) Math.round(v)).build(),
                        CalculationCandidate::maghribAdjustmentSeconds);
                result.sunriseAltitudeDegrees(alt).sunriseAdjustmentSeconds(sunriseBuffer)
                        .maghribAltitudeDegrees(alt).maghribAdjustmentSeconds(maghribBuffer);
            }
            case FITTED_SEPARATE -> {
                double sunriseAlt = fitDouble(training, ranges.sunriseAltitude(), Prayer.SUNRISE,
                        v -> structural.get().sunriseAltitudeDegrees(v).build(),
                        CalculationCandidate::sunriseAltitudeDegrees);
                double maghribAlt = fitDouble(training, ranges.maghribAltitude(), Prayer.MAGHRIB,
                        v -> structural.get().maghribAltitudeDegrees(v).build(),
                        CalculationCandidate::maghribAltitudeDegrees);
                result.sunriseAltitudeDegrees(sunriseAlt).maghribAltitudeDegrees(maghribAlt);
            }
            default -> {
                // FITTED_SHARED (with or without elevation): one altitude scored
                // jointly across sunrise and Maghrib, then applied to both.
                double shared = fitShared(training, structural);
                result.sunriseAltitudeDegrees(shared).maghribAltitudeDegrees(shared);
            }
        }
    }

    private double fitShared(
            ReferenceDataset training, Supplier<CalculationCandidate.Builder> structural) {
        List<Prayer> both = List.of(Prayer.SUNRISE, Prayer.MAGHRIB);
        CandidateResult best = null;
        for (double v : ranges.sunriseAltitude().values()) {
            CalculationCandidate candidate = structural.get()
                    .sunriseAltitudeDegrees(v).maghribAltitudeDegrees(v).build();
            CandidateResult r =
                    CandidateResult.evaluate(candidate, engine, training, both, locations);
            if (best == null || CandidateResult.RANKING.compare(r, best) < 0) {
                best = r;
            }
        }
        return best.candidate().sunriseAltitudeDegrees();
    }

    // --- Generic one-parameter fitters ---------------------------------------

    private double fitDouble(
            ReferenceDataset training, ParameterRange range, Prayer prayer,
            DoubleFunction<CalculationCandidate> factory,
            java.util.function.ToDoubleFunction<CalculationCandidate> extractor) {
        return extractor.applyAsDouble(fitBest(training, range, prayer, factory));
    }

    private int fitInt(
            ReferenceDataset training, ParameterRange range, Prayer prayer,
            DoubleFunction<CalculationCandidate> factory,
            java.util.function.ToIntFunction<CalculationCandidate> extractor) {
        return extractor.applyAsInt(fitBest(training, range, prayer, factory));
    }

    private CalculationCandidate fitBest(
            ReferenceDataset training, ParameterRange range, Prayer prayer,
            DoubleFunction<CalculationCandidate> factory) {
        List<Prayer> scored = List.of(prayer);
        CandidateResult best = null;
        for (double v : range.values()) {
            CandidateResult r =
                    CandidateResult.evaluate(factory.apply(v), engine, training, scored, locations);
            if (best == null || CandidateResult.RANKING.compare(r, best) < 0) {
                best = r;
            }
        }
        return best.candidate();
    }
}
