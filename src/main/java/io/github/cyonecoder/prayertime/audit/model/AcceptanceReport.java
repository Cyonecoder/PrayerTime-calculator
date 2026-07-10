package io.github.cyonecoder.prayertime.audit.model;

import io.github.cyonecoder.prayertime.audit.optimization.CandidateResult;
import io.github.cyonecoder.prayertime.audit.optimization.CandidateResult.Residual;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates a candidate's residuals against the Prompt-3 acceptance targets.
 *
 * <p>The targets are deliberately not a 100% claim; they are the bar a global
 * candidate must clear to <em>proceed</em>:
 * <ul>
 *   <li>overall mean absolute error ≤ 0.50 minute;</li>
 *   <li>every prayer's maximum absolute error ≤ 2 minutes;</li>
 *   <li>Chourouk exact-match rate ≥ 95%;</li>
 *   <li>the five obligatory prayers' exact-match rate ≥ 95%;</li>
 *   <li>no city with a systematic mean error above 1 minute;</li>
 *   <li>no month with a systematic mean error above 1 minute.</li>
 * </ul>
 */
public record AcceptanceReport(
        double overallMeanAbsoluteError,
        double maxPrayerMaxAbsoluteError,
        double chouroukExactPercentage,
        double obligatoryExactPercentage,
        double worstCityAbsMeanError,
        double worstMonthAbsMeanError,
        Map<Prayer, Integer> perPrayerMaxAbsError,
        Map<String, Double> perCityMeanSignedError,
        Map<YearMonth, Double> perMonthMeanSignedError,
        boolean maeOk,
        boolean maxErrorOk,
        boolean chouroukExactOk,
        boolean obligatoryExactOk,
        boolean noCityBiasOk,
        boolean noSeasonBiasOk) {

    /** The five obligatory prayers (Chourouk/sunrise excluded). */
    public static final Set<Prayer> OBLIGATORY =
            Set.of(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA);

    public static final double MAE_TARGET = 0.50;
    public static final int MAX_ERROR_TARGET = 2;
    public static final double EXACT_TARGET_PERCENT = 95.0;
    public static final double CITY_BIAS_TARGET = 1.0;
    public static final double SEASON_BIAS_TARGET = 1.0;

    /** @return whether every target passes. */
    public boolean allTargetsMet() {
        return maeOk && maxErrorOk && chouroukExactOk && obligatoryExactOk
                && noCityBiasOk && noSeasonBiasOk;
    }

    /**
     * Evaluates the targets from an evaluated candidate.
     *
     * @param result the candidate result whose residuals to score
     * @return the acceptance report
     */
    public static AcceptanceReport evaluate(CandidateResult result) {
        List<Residual> residuals = result.residuals();

        long absSum = 0;
        int count = 0;
        Map<Prayer, Integer> prayerMax = new EnumMap<>(Prayer.class);
        int chouroukExact = 0;
        int chouroukTotal = 0;
        int obligatoryExact = 0;
        int obligatoryTotal = 0;
        Map<String, long[]> citySums = new LinkedHashMap<>();
        Map<YearMonth, long[]> monthSums = new LinkedHashMap<>();

        for (Residual r : residuals) {
            int signed = r.signedErrorMinutes();
            int abs = r.absoluteErrorMinutes();
            absSum += abs;
            count++;
            prayerMax.merge(r.prayer(), abs, Math::max);
            if (r.prayer() == Prayer.SUNRISE) {
                chouroukTotal++;
                if (abs == 0) {
                    chouroukExact++;
                }
            }
            if (OBLIGATORY.contains(r.prayer())) {
                obligatoryTotal++;
                if (abs == 0) {
                    obligatoryExact++;
                }
            }
            long[] city = citySums.computeIfAbsent(r.city(), k -> new long[2]);
            city[0] += signed;
            city[1] += 1;
            YearMonth month = YearMonth.from(r.date());
            long[] m = monthSums.computeIfAbsent(month, k -> new long[2]);
            m[0] += signed;
            m[1] += 1;
        }

        double overallMae = count == 0 ? 0.0 : (double) absSum / count;
        int maxPrayerMax = prayerMax.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        double chouroukPct = chouroukTotal == 0 ? 0.0 : 100.0 * chouroukExact / chouroukTotal;
        double obligatoryPct = obligatoryTotal == 0 ? 0.0 : 100.0 * obligatoryExact / obligatoryTotal;

        Map<String, Double> cityMean = new LinkedHashMap<>();
        double worstCity = 0.0;
        for (Map.Entry<String, long[]> e : citySums.entrySet()) {
            double mean = (double) e.getValue()[0] / e.getValue()[1];
            cityMean.put(e.getKey(), mean);
            worstCity = Math.max(worstCity, Math.abs(mean));
        }
        Map<YearMonth, Double> monthMean = new LinkedHashMap<>();
        double worstMonth = 0.0;
        for (Map.Entry<YearMonth, long[]> e : monthSums.entrySet()) {
            double mean = (double) e.getValue()[0] / e.getValue()[1];
            monthMean.put(e.getKey(), mean);
            worstMonth = Math.max(worstMonth, Math.abs(mean));
        }

        return new AcceptanceReport(
                overallMae, maxPrayerMax, chouroukPct, obligatoryPct, worstCity, worstMonth,
                prayerMax, cityMean, monthMean,
                overallMae <= MAE_TARGET,
                maxPrayerMax <= MAX_ERROR_TARGET,
                chouroukPct >= EXACT_TARGET_PERCENT,
                obligatoryPct >= EXACT_TARGET_PERCENT,
                worstCity <= CITY_BIAS_TARGET,
                worstMonth <= SEASON_BIAS_TARGET);
    }
}
