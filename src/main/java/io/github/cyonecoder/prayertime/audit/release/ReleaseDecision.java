package io.github.cyonecoder.prayertime.audit.release;

import io.github.cyonecoder.prayertime.audit.release.HabousMethodValidator.Group;
import io.github.cyonecoder.prayertime.audit.release.HabousMethodValidator.GroupResult;
import io.github.cyonecoder.prayertime.audit.release.HabousMethodValidator.ValidationResult;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies the Prompt-6 release thresholds to a {@link ValidationResult} and
 * produces an honest GO / NO-GO decision. Metrics are read as-is: nothing is
 * softened to make a gate pass.
 */
public record ReleaseDecision(
        boolean reconstructedMethodPass,
        double reconstructedExactPercentage,
        double reconstructedWithinOnePercentage,
        double reconstructedWorstCityBias,
        List<String> reconstructedFailures,
        boolean habousCompatiblePass,
        double habousCompatibleExactPercentage,
        int habousCompatibleUnmatched,
        int unreviewedCorrections,
        int unreviewedOverrides,
        int staleCorrections,
        int staleOverrides,
        int missingDates,
        List<String> habousCompatibleFailures,
        boolean pureFormulaReaches100,
        boolean releaseApproved) {

    public static final double RECONSTRUCTED_EXACT_MIN = 99.0;
    public static final double WITHIN_ONE_REQUIRED = 100.0;
    public static final double CITY_BIAS_MAX = 1.0;
    public static final double OFFICIAL_EXACT_REQUIRED = 100.0;

    /**
     * Evaluates both gates.
     *
     * @param result the validation result across the three groups
     * @return the release decision
     */
    public static ReleaseDecision evaluate(ValidationResult result) {
        GroupResult formula = result.group(Group.FORMULA_ONLY);
        GroupResult official = result.group(Group.FORMULA_PLUS_CORRECTIONS_PLUS_OVERRIDES);

        // --- Reconstructed calculation method (evaluated on the pure formula) ---
        List<String> reconFailures = new ArrayList<>();
        if (formula.exactPercentage() < RECONSTRUCTED_EXACT_MIN) {
            reconFailures.add(String.format(java.util.Locale.ROOT,
                    "exact-minute agreement %.2f%% < required %.1f%%",
                    formula.exactPercentage(), RECONSTRUCTED_EXACT_MIN));
        }
        if (formula.withinOnePercentage() < WITHIN_ONE_REQUIRED) {
            reconFailures.add(String.format(java.util.Locale.ROOT,
                    "within-one-minute agreement %.2f%% < required %.1f%%",
                    formula.withinOnePercentage(), WITHIN_ONE_REQUIRED));
        }
        if (formula.worstCityAbsMeanSigned() > CITY_BIAS_MAX) {
            reconFailures.add(String.format(java.util.Locale.ROOT,
                    "unexplained systematic residual: worst city mean bias %.2f min > %.1f",
                    formula.worstCityAbsMeanSigned(), CITY_BIAS_MAX));
        }
        boolean reconPass = reconFailures.isEmpty();

        // --- Habous-compatible published mode (fully corrected group) ----------
        List<String> officialFailures = new ArrayList<>();
        if (official.exactPercentage() < OFFICIAL_EXACT_REQUIRED) {
            officialFailures.add(String.format(java.util.Locale.ROOT,
                    "exact-minute agreement %.2f%% < required 100%%", official.exactPercentage()));
        }
        if (official.unmatchedRecords() > 0) {
            officialFailures.add(official.unmatchedRecords() + " unmatched official observations");
        }
        if (!result.unapprovedCorrections().isEmpty()) {
            officialFailures.add(result.unapprovedCorrections().size() + " unreviewed corrections");
        }
        if (!result.unreviewedOverrides().isEmpty()) {
            officialFailures.add(result.unreviewedOverrides().size() + " unreviewed overrides");
        }
        if (!result.staleCorrections().isEmpty()) {
            officialFailures.add(result.staleCorrections().size()
                    + " corrections with changed source checksums (require review)");
        }
        if (!result.staleOverrides().isEmpty()) {
            officialFailures.add(result.staleOverrides().size()
                    + " overrides with changed source checksums (require review)");
        }
        if (result.missingDates() > 0) {
            officialFailures.add(result.missingDates() + " missing dates in the official coverage");
        }
        boolean officialPass = officialFailures.isEmpty();

        boolean pureReaches100 = formula.exactPercentage() >= OFFICIAL_EXACT_REQUIRED;

        return new ReleaseDecision(
                reconPass, formula.exactPercentage(), formula.withinOnePercentage(),
                formula.worstCityAbsMeanSigned(), reconFailures,
                officialPass, official.exactPercentage(), official.unmatchedRecords(),
                result.unapprovedCorrections().size(), result.unreviewedOverrides().size(),
                result.staleCorrections().size(), result.staleOverrides().size(),
                result.missingDates(), officialFailures,
                pureReaches100, reconPass && officialPass);
    }
}
