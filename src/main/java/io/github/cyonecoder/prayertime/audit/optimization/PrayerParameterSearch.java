package io.github.cyonecoder.prayertime.audit.optimization;

import io.github.cyonecoder.prayertime.audit.optimization.ParameterSearchService.PrayerSearchResult;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleFunction;

/**
 * Orchestrates the audit: each prayer's primary parameter is searched
 * independently (one dimension at a time), then a single small combined
 * validation is run using only each prayer's best value. The full Cartesian
 * product over all prayers is never enumerated.
 *
 * <p>Search order follows the prompt: Dhuhr, Asr, Fajr, Isha, Chourouk
 * (sunrise), Maghrib.
 */
public final class PrayerParameterSearch {

    /** The searched prayers, in the required order. */
    public static final List<Prayer> SEARCH_ORDER = List.of(
            Prayer.DHUHR, Prayer.ASR, Prayer.FAJR, Prayer.ISHA, Prayer.SUNRISE, Prayer.MAGHRIB);

    /**
     * The configurable per-parameter search ranges.
     *
     * @param fajrAngle       Fajr twilight angle grid
     * @param sunriseAltitude Chourouk (sunrise) altitude grid
     * @param dhuhrAdjustment Dhuhr adjustment grid, in seconds
     * @param asrShadowFactor Asr shadow-factor grid
     * @param maghribAltitude Maghrib altitude grid
     * @param ishaAngle       Isha twilight angle grid
     */
    public record Ranges(
            ParameterRange fajrAngle,
            ParameterRange sunriseAltitude,
            ParameterRange dhuhrAdjustment,
            ParameterRange asrShadowFactor,
            ParameterRange maghribAltitude,
            ParameterRange ishaAngle) {

        /** @return the initial ranges specified for the Habous audit. */
        public static Ranges defaults() {
            return new Ranges(
                    ParameterRange.of("fajrAngle", 17.0, 20.5, 0.001),
                    ParameterRange.of("sunriseAltitudeDegrees", -2.50, -0.70, 0.001),
                    ParameterRange.of("dhuhrAdjustmentSeconds", 0, 600, 1),
                    ParameterRange.of("asrShadowFactor", 0.95, 1.05, 0.0001),
                    ParameterRange.of("maghribAltitudeDegrees", -2.50, -0.70, 0.001),
                    ParameterRange.of("ishaAngle", 15.0, 20.0, 0.001));
        }

        /** @return the range for the given prayer's primary parameter. */
        public ParameterRange forPrayer(Prayer prayer) {
            return switch (prayer) {
                case FAJR -> fajrAngle;
                case SUNRISE -> sunriseAltitude;
                case DHUHR -> dhuhrAdjustment;
                case ASR -> asrShadowFactor;
                case MAGHRIB -> maghribAltitude;
                case ISHA -> ishaAngle;
            };
        }
    }

    /** The combined-validation outcome across all searched prayers. */
    public record CombinedResult(
            Map<Prayer, PrayerSearchResult> perPrayer,
            CalculationCandidate combinedCandidate,
            CandidateResult combined) {
    }

    private final ParameterSearchService service;
    private final Ranges ranges;

    public PrayerParameterSearch(ParameterSearchService service) {
        this(service, Ranges.defaults());
    }

    public PrayerParameterSearch(ParameterSearchService service, Ranges ranges) {
        this.service = Objects.requireNonNull(service, "service");
        this.ranges = Objects.requireNonNull(ranges, "ranges");
    }

    public Ranges ranges() {
        return ranges;
    }

    /**
     * Searches one prayer's primary parameter independently.
     *
     * @param prayer  the prayer
     * @param dataset the reference observations
     * @param policy  the fixed rounding policy
     * @param order   the fixed operation order
     * @return the one-parameter search result
     */
    public PrayerSearchResult searchPrayer(
            Prayer prayer, ReferenceDataset dataset, RoundingPolicy policy, OperationOrder order) {
        ParameterRange range = ranges.forPrayer(prayer);
        DoubleFunction<CalculationCandidate> factory = factoryFor(prayer, policy, order);
        return service.search(prayer, range, dataset, factory);
    }

    /**
     * Runs the independent per-prayer searches then a single combined
     * validation using each prayer's best value.
     *
     * @param dataset the reference observations
     * @param policy  the fixed rounding policy
     * @param order   the fixed operation order
     * @return the combined result, including every per-prayer search
     */
    public CombinedResult searchAll(
            ReferenceDataset dataset, RoundingPolicy policy, OperationOrder order) {
        Map<Prayer, PrayerSearchResult> perPrayer = new LinkedHashMap<>();
        for (Prayer prayer : SEARCH_ORDER) {
            perPrayer.put(prayer, searchPrayer(prayer, dataset, policy, order));
        }

        CalculationCandidate.Builder builder = CalculationCandidate.builder()
                .roundingPolicyFixed(policy)
                .operationOrderFixed(order);
        for (Prayer prayer : SEARCH_ORDER) {
            PrayerSearchResult result = perPrayer.get(prayer);
            result.best().ifPresent(best -> applyPrimary(builder, prayer, best.candidate()));
        }
        CalculationCandidate combinedCandidate = builder.build();

        List<Prayer> allPrayers = new ArrayList<>(SEARCH_ORDER);
        CandidateResult combined = CandidateResult.evaluate(
                combinedCandidate, service.engine(), dataset, allPrayers, service.locations());
        return new CombinedResult(perPrayer, combinedCandidate, combined);
    }

    // --- Per-prayer knob wiring ----------------------------------------------

    private DoubleFunction<CalculationCandidate> factoryFor(
            Prayer prayer, RoundingPolicy policy, OperationOrder order) {
        return value -> {
            CalculationCandidate.Builder b = CalculationCandidate.builder()
                    .roundingPolicyFixed(policy)
                    .operationOrderFixed(order);
            applyGridValue(b, prayer, value);
            return b.build();
        };
    }

    private static void applyGridValue(CalculationCandidate.Builder b, Prayer prayer, double value) {
        switch (prayer) {
            case FAJR -> b.fajrAngle(value);
            case SUNRISE -> b.sunriseAltitudeDegrees(value);
            case DHUHR -> b.dhuhrAdjustmentSeconds((int) Math.round(value));
            case ASR -> b.asrShadowFactor(value);
            case MAGHRIB -> b.maghribAltitudeDegrees(value);
            case ISHA -> b.ishaAngle(value);
        }
    }

    private static void applyPrimary(
            CalculationCandidate.Builder b, Prayer prayer, CalculationCandidate best) {
        switch (prayer) {
            case FAJR -> b.fajrAngle(best.fajrAngle());
            case SUNRISE -> b.sunriseAltitudeDegrees(best.sunriseAltitudeDegrees());
            case DHUHR -> b.dhuhrAdjustmentSeconds(best.dhuhrAdjustmentSeconds());
            case ASR -> b.asrShadowFactor(best.asrShadowFactor());
            case MAGHRIB -> b.maghribAltitudeDegrees(best.maghribAltitudeDegrees());
            case ISHA -> b.ishaAngle(best.ishaAngle());
        }
    }
}
