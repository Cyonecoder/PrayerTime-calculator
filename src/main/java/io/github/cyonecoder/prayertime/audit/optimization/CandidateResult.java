package io.github.cyonecoder.prayertime.audit.optimization;

import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset.Observation;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The outcome of evaluating one {@link CalculationCandidate} over a set of
 * reference observations for one or more prayers: the per-comparison residuals
 * and the aggregate {@link CandidateScore}.
 *
 * <p>Immutable. Instances are produced by {@link #evaluate} and ranked with
 * {@link #RANKING}, which applies {@link CandidateScore#RANKING} and then a
 * deterministic tie-break on the candidate signature so ordering is fully
 * reproducible.
 */
public final class CandidateResult {

    /**
     * One computed-vs-official comparison.
     *
     * @param city                  city slug
     * @param date                  the date
     * @param prayer                the prayer
     * @param computedMinuteOfDay   the candidate's published minute of day
     * @param officialMinuteOfDay   the reference published minute of day
     * @param rawSecondsOfDay       the candidate's raw (pre-round) seconds of day
     * @param officialSecondsOfDay  the reference seconds of day (minute boundary)
     */
    public record Residual(
            String city, LocalDate date, Prayer prayer,
            int computedMinuteOfDay, int officialMinuteOfDay,
            double rawSecondsOfDay, int officialSecondsOfDay) {

        /** @return signed minute error (computed - official). */
        public int signedErrorMinutes() {
            return computedMinuteOfDay - officialMinuteOfDay;
        }

        /** @return absolute minute error. */
        public int absoluteErrorMinutes() {
            return Math.abs(signedErrorMinutes());
        }

        /** @return signed raw-second residual (raw computed - official), retaining sub-minute detail. */
        public double rawSecondDifference() {
            return normalizedRawSeconds() - officialSecondsOfDay;
        }

        private double normalizedRawSeconds() {
            double mod = rawSecondsOfDay % 86_400.0;
            return mod < 0 ? mod + 86_400.0 : mod;
        }
    }

    private final CalculationCandidate candidate;
    private final List<Residual> residuals;
    private final CandidateScore score;

    private CandidateResult(CalculationCandidate candidate, List<Residual> residuals, CandidateScore score) {
        this.candidate = candidate;
        this.residuals = List.copyOf(residuals);
        this.score = score;
    }

    /**
     * Evaluates a candidate against the dataset for the given prayers.
     *
     * @param candidate the parameter set
     * @param engine    the raw calculation engine
     * @param dataset   the reference observations
     * @param prayers   the prayers to score
     * @param locations resolves a city slug to its coordinates and zone
     * @return the evaluated result
     */
    public static CandidateResult evaluate(
            CalculationCandidate candidate,
            HabousRawEngine engine,
            ReferenceDataset dataset,
            List<Prayer> prayers,
            CityLocations locations) {
        Objects.requireNonNull(candidate, "candidate");
        List<Residual> residuals = new ArrayList<>();
        for (Observation obs : dataset.observations()) {
            CityLocations.Location loc = locations.resolve(obs.city());
            for (Prayer prayer : prayers) {
                int computed = engine.publishedMinuteOfDay(
                        prayer, candidate, loc.coordinates(), loc.zone(), obs.date());
                double rawSeconds = engine.rawSecondsOfDay(
                        prayer, candidate, loc.coordinates(), loc.zone(), obs.date());
                int officialMin = obs.minuteOfDayFor(prayer);
                residuals.add(new Residual(
                        obs.city(), obs.date(), prayer,
                        computed, officialMin, rawSeconds, officialMin * 60));
            }
        }
        return new CandidateResult(candidate, residuals, computeScore(candidate, residuals));
    }

    private static CandidateScore computeScore(CalculationCandidate candidate, List<Residual> residuals) {
        int total = residuals.size();
        int exact = 0;
        int withinOne = 0;
        int maxAbs = 0;
        long sumSigned = 0;
        long sumAbs = 0;
        java.util.Set<String> cities = new java.util.HashSet<>();
        java.util.Set<LocalDate> dates = new java.util.HashSet<>();
        java.util.Set<Prayer> prayers = new java.util.HashSet<>();
        // Per-city exact counters for cross-city stability.
        Map<String, int[]> perCity = new LinkedHashMap<>();

        for (Residual r : residuals) {
            int signed = r.signedErrorMinutes();
            int abs = r.absoluteErrorMinutes();
            if (abs == 0) {
                exact++;
            }
            if (abs <= 1) {
                withinOne++;
            }
            maxAbs = Math.max(maxAbs, abs);
            sumSigned += signed;
            sumAbs += abs;
            cities.add(r.city());
            dates.add(r.date());
            prayers.add(r.prayer());
            int[] counters = perCity.computeIfAbsent(r.city(), k -> new int[2]);
            counters[0] += (abs == 0 ? 1 : 0);
            counters[1] += 1;
        }

        double meanSigned = total == 0 ? 0.0 : (double) sumSigned / total;
        double meanAbs = total == 0 ? 0.0 : (double) sumAbs / total;

        double variance = 0.0;
        for (Residual r : residuals) {
            double d = r.signedErrorMinutes() - meanSigned;
            variance += d * d;
        }
        double stdDev = total == 0 ? 0.0 : Math.sqrt(variance / total);

        double crossCityStability = 1.0;
        for (int[] counters : perCity.values()) {
            double fraction = counters[1] == 0 ? 0.0 : (double) counters[0] / counters[1];
            crossCityStability = Math.min(crossCityStability, fraction);
        }

        return new CandidateScore(
                exact, withinOne, total, meanSigned, meanAbs, maxAbs, stdDev,
                cities.size(), dates.size(), prayers.size(), candidate.parameterCount(),
                crossCityStability);
    }

    public CalculationCandidate candidate() {
        return candidate;
    }

    public List<Residual> residuals() {
        return residuals;
    }

    public CandidateScore score() {
        return score;
    }

    /**
     * A stable textual signature of the candidate, used only as the final
     * deterministic tie-breaker so equally-scored candidates always sort the
     * same way.
     *
     * @return the signature
     */
    public String signature() {
        return String.format(java.util.Locale.ROOT,
                "fajr=%.4f;isha=%.4f;asr=%.4f;sunrise=%.4f;maghrib=%.4f;"
                        + "adj=%d,%d,%d,%d,%d,%d;round=%s;order=%s",
                candidate.fajrAngle(), candidate.ishaAngle(), candidate.asrShadowFactor(),
                candidate.sunriseAltitudeDegrees(), candidate.maghribAltitudeDegrees(),
                candidate.fajrAdjustmentSeconds(), candidate.sunriseAdjustmentSeconds(),
                candidate.dhuhrAdjustmentSeconds(), candidate.asrAdjustmentSeconds(),
                candidate.maghribAdjustmentSeconds(), candidate.ishaAdjustmentSeconds(),
                candidate.roundingPolicy(), candidate.operationOrder());
    }

    /** Best-first ranking: score priority then a deterministic signature tie-break. */
    public static final Comparator<CandidateResult> RANKING =
            Comparator.comparing(CandidateResult::score, CandidateScore.RANKING)
                    .thenComparing(CandidateResult::signature);
}
