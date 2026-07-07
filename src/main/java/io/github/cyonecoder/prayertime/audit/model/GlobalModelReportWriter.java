package io.github.cyonecoder.prayertime.audit.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.cyonecoder.prayertime.audit.model.GlobalModelSelection.FamilyOutcome;
import io.github.cyonecoder.prayertime.audit.model.GlobalModelSelection.Provenance;
import io.github.cyonecoder.prayertime.audit.model.GlobalModelSelection.SelectionResult;
import io.github.cyonecoder.prayertime.audit.optimization.CalculationCandidate;
import io.github.cyonecoder.prayertime.audit.optimization.CandidateResult;
import io.github.cyonecoder.prayertime.audit.optimization.CandidateScore;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Writes the eight Prompt-3 deliverables into a report directory:
 *
 * <pre>
 * build/reports/habous-global-model/
 * ├── selected-model.json      the frozen global candidate
 * ├── selected-model.md        a readable summary of the selection
 * ├── training-results.csv     per-family training metrics
 * ├── validation-results.csv   per-family validation metrics
 * ├── holdout-results.csv      the selected model on the (once-only) holdout
 * ├── residual-analysis.md     residuals by every axis, plus detections
 * ├── model-comparison.csv     every family side by side with target flags
 * └── reproducibility.json     freeze timestamp, checksums and code commit
 * </pre>
 *
 * <p>All numeric formatting uses {@link Locale#ROOT} for byte-stable output.
 */
public final class GlobalModelReportWriter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** The eight files this writer always produces (relative to the output dir). */
    public static final List<String> FILES = List.of(
            "selected-model.json", "selected-model.md", "training-results.csv",
            "validation-results.csv", "holdout-results.csv", "residual-analysis.md",
            "model-comparison.csv", "reproducibility.json");

    /**
     * Writes all eight files into {@code outputDir}, creating it if needed.
     *
     * @param outputDir the report directory
     * @param result    the selection outcome to render
     * @return the output directory
     */
    public Path write(Path outputDir, SelectionResult result) {
        Objects.requireNonNull(outputDir, "outputDir");
        Objects.requireNonNull(result, "result");
        try {
            Files.createDirectories(outputDir);
            writeSelectedModelJson(outputDir.resolve("selected-model.json"), result);
            writeSelectedModelMd(outputDir.resolve("selected-model.md"), result);
            writeFamilyCsv(outputDir.resolve("training-results.csv"), result, false);
            writeFamilyCsv(outputDir.resolve("validation-results.csv"), result, true);
            writeHoldoutCsv(outputDir.resolve("holdout-results.csv"), result);
            writeResidualAnalysisMd(outputDir.resolve("residual-analysis.md"), result);
            writeModelComparisonCsv(outputDir.resolve("model-comparison.csv"), result);
            writeReproducibilityJson(outputDir.resolve("reproducibility.json"), result);
            return outputDir;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write global-model report to " + outputDir, e);
        }
    }

    // --- selected-model.* -----------------------------------------------------

    private void writeSelectedModelJson(Path path, SelectionResult r) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("selectedFamily", r.selectedFamily().name());
        m.put("label", r.selectedFamily().label());
        m.put("activated", false);
        m.put("productionDefaultMethodId", "morocco");
        m.put("candidateMethodId", "morocco-habous-candidate");
        m.put("candidate", CandidateSerialization.toDto(r.selectedCandidate()));
        m.put("provenance", provenanceMap(r.provenance()));
        writeJson(path, m);
    }

    private void writeSelectedModelMd(Path path, SelectionResult r) throws IOException {
        CalculationCandidate c = r.selectedCandidate();
        try (Writer w = newWriter(path)) {
            w.write("# Selected global Habous model (candidate)\n\n");
            w.write("**Family:** " + r.selectedFamily().name()
                    + " — " + r.selectedFamily().label() + "\n\n");
            w.write("**Status:** NOT activated. Production default remains `morocco` "
                    + "(MoroccoCalculationMethod). This candidate is exposed as "
                    + "`morocco-habous-candidate`.\n\n");
            w.write("## Parameters\n\n");
            w.write("| Parameter | Value |\n|---|---|\n");
            w.write(row("Fajr angle", fmt(c.fajrAngle()) + "°"));
            w.write(row("Isha angle", fmt(c.ishaAngle()) + "°"));
            w.write(row("Asr shadow factor", fmt(c.asrShadowFactor())));
            w.write(row("Sunrise altitude", fmt(c.sunriseAltitudeDegrees()) + "°"));
            w.write(row("Maghrib altitude", fmt(c.maghribAltitudeDegrees()) + "°"));
            w.write(row("Elevation-adjusted horizon", String.valueOf(c.elevationAdjustedHorizon())));
            w.write(row("Dhuhr adjustment", c.dhuhrAdjustmentSeconds() + " s"));
            w.write(row("Sunrise adjustment", c.sunriseAdjustmentSeconds() + " s"));
            w.write(row("Maghrib adjustment", c.maghribAdjustmentSeconds() + " s"));
            w.write(row("Rounding policy", c.roundingPolicy().name()));
            w.write(row("Operation order", c.operationOrder().name()));
            w.write("\n## Acceptance (validation)\n\n");
            r.outcomes().stream()
                    .filter(o -> o.family() == r.selectedFamily())
                    .findFirst()
                    .flatMap(FamilyOutcome::validationAcceptance)
                    .ifPresentOrElse(a -> writeAcceptance(w, a),
                            () -> safeWrite(w, "No validation partition available.\n"));
            w.write("\n## Holdout\n\n");
            if (r.holdoutResult().isPresent()) {
                CandidateScore s = r.holdoutResult().get().score();
                safeWrite(w, String.format(Locale.ROOT,
                        "Scored once after freeze: exact %.1f%%, MAE %.3f, maxErr %d over %d comparisons.\n",
                        s.exactPercentage(), s.meanAbsoluteErrorMinutes(),
                        s.maximumAbsoluteErrorMinutes(), s.total()));
            } else {
                safeWrite(w, "No holdout observations present in the current snapshot; "
                        + "holdout evaluation deferred until holdout cities are collected.\n");
            }
        }
    }

    private void writeAcceptance(Writer w, AcceptanceReport a) {
        safeWrite(w, "| Target | Value | Met |\n|---|---|---|\n");
        safeWrite(w, targetRow("Overall MAE ≤ 0.50", fmt(a.overallMeanAbsoluteError()), a.maeOk()));
        safeWrite(w, targetRow("Every prayer maxErr ≤ 2",
                String.valueOf((int) a.maxPrayerMaxAbsoluteError()), a.maxErrorOk()));
        safeWrite(w, targetRow("Chourouk exact ≥ 95%",
                fmt(a.chouroukExactPercentage()) + "%", a.chouroukExactOk()));
        safeWrite(w, targetRow("Obligatory exact ≥ 95%",
                fmt(a.obligatoryExactPercentage()) + "%", a.obligatoryExactOk()));
        safeWrite(w, targetRow("No city bias > 1 min", fmt(a.worstCityAbsMeanError()), a.noCityBiasOk()));
        safeWrite(w, targetRow("No season bias > 1 min", fmt(a.worstMonthAbsMeanError()), a.noSeasonBiasOk()));
    }

    // --- training/validation CSV ---------------------------------------------

    private void writeFamilyCsv(Path path, SelectionResult r, boolean validation) throws IOException {
        try (Writer w = newWriter(path)) {
            w.write("family,selected,finalist,exactPercentage,meanAbsoluteErrorMinutes,"
                    + "maximumAbsoluteErrorMinutes,meanSignedErrorMinutes,total,parameterCount\n");
            for (FamilyOutcome o : r.outcomes()) {
                Optional<CandidateResult> res = validation
                        ? o.validationResult() : Optional.of(o.trainingResult());
                if (res.isEmpty()) {
                    w.write(o.family().name() + ",,,,,,,,\n");
                    continue;
                }
                CandidateScore s = res.get().score();
                w.write(String.format(Locale.ROOT, "%s,%s,%s,%.4f,%.4f,%d,%.4f,%d,%d\n",
                        o.family().name(), o.family() == r.selectedFamily(), o.finalist(),
                        s.exactPercentage(), s.meanAbsoluteErrorMinutes(),
                        s.maximumAbsoluteErrorMinutes(), s.meanSignedErrorMinutes(),
                        s.total(), s.parameterCount()));
            }
        }
    }

    private void writeHoldoutCsv(Path path, SelectionResult r) throws IOException {
        try (Writer w = newWriter(path)) {
            w.write("city,date,prayer,computedMinuteOfDay,officialMinuteOfDay,signedErrorMinutes\n");
            if (r.holdoutResult().isEmpty()) {
                w.write("# no holdout observations present in current snapshot\n");
                return;
            }
            for (CandidateResult.Residual res : r.holdoutResult().get().residuals()) {
                w.write(String.format(Locale.ROOT, "%s,%s,%s,%d,%d,%d\n",
                        res.city(), res.date(), res.prayer(),
                        res.computedMinuteOfDay(), res.officialMinuteOfDay(),
                        res.signedErrorMinutes()));
            }
        }
    }

    // --- residual-analysis.md -------------------------------------------------

    private void writeResidualAnalysisMd(Path path, SelectionResult r) throws IOException {
        ResidualAnalysis a = r.residualAnalysis();
        try (Writer w = newWriter(path)) {
            w.write("# Residual analysis — " + r.selectedFamily().name() + "\n\n");
            w.write("Computed over the fit+validation visible data (holdout excluded).\n\n");
            groupSection(w, "Residuals by prayer", a.byPrayer(), Prayer::name);
            groupSection(w, "Residuals by city", a.byCity(), s -> s);
            groupSection(w, "Residuals by month", a.byMonth(), YearMonth::toString);
            groupSection(w, "Residuals by latitude", a.byLatitude(), GlobalModelReportWriter::fmt);
            groupSection(w, "Residuals by longitude", a.byLongitude(), GlobalModelReportWriter::fmt);
            groupSection(w, "Residuals by elevation (m)", a.byElevation(), GlobalModelReportWriter::fmt);
            groupSection(w, "Residuals by UTC offset (h)", a.byUtcOffsetHours(), String::valueOf);

            w.write("## Signed-error frequency\n\n");
            w.write("| signedErrorMinutes | count |\n|---|---|\n");
            a.signedErrorFrequency().forEach((k, v) -> safeWrite(w, "| " + k + " | " + v + " |\n"));

            w.write("\n## Abrupt official date changes\n\n");
            writeList(w, a.abruptDateChanges());

            w.write("\n## Detections\n\n");
            writeList(w, a.detections());
        }
    }

    private <K> void groupSection(
            Writer w, String title, Map<K, ResidualGroup> groups,
            java.util.function.Function<K, String> keyLabel) {
        safeWrite(w, "## " + title + "\n\n");
        safeWrite(w, "| key | count | meanSigned | meanAbs | maxAbs |\n|---|---|---|---|---|\n");
        groups.forEach((k, g) -> safeWrite(w, String.format(Locale.ROOT,
                "| %s | %d | %+.3f | %.3f | %d |\n",
                keyLabel.apply(k), g.count(), g.meanSigned(), g.meanAbsolute(), g.maxAbsolute())));
        safeWrite(w, "\n");
    }

    private void writeList(Writer w, List<String> items) {
        if (items.isEmpty()) {
            safeWrite(w, "_None._\n");
            return;
        }
        for (String item : items) {
            safeWrite(w, "- " + item + "\n");
        }
    }

    // --- model-comparison.csv -------------------------------------------------

    private void writeModelComparisonCsv(Path path, SelectionResult r) throws IOException {
        try (Writer w = newWriter(path)) {
            w.write("family,label,selected,finalist,trainingExactPercentage,validationExactPercentage,"
                    + "validationMeanAbsoluteError,validationMaxAbsoluteError,parameterCount,"
                    + "maeOk,maxErrorOk,chouroukExactOk,obligatoryExactOk,noCityBiasOk,noSeasonBiasOk,"
                    + "allTargetsMet\n");
            for (FamilyOutcome o : r.outcomes()) {
                double trainExact = o.trainingResult().score().exactPercentage();
                Optional<CandidateScore> vs = o.validationResult().map(CandidateResult::score);
                Optional<AcceptanceReport> va = o.validationAcceptance();
                w.write(String.format(Locale.ROOT,
                        "%s,%s,%s,%s,%.4f,%s,%s,%s,%d,%s,%s,%s,%s,%s,%s,%s\n",
                        o.family().name(), o.family().label(),
                        o.family() == r.selectedFamily(), o.finalist(),
                        trainExact,
                        vs.map(s -> String.format(Locale.ROOT, "%.4f", s.exactPercentage())).orElse(""),
                        vs.map(s -> String.format(Locale.ROOT, "%.4f", s.meanAbsoluteErrorMinutes())).orElse(""),
                        vs.map(s -> String.valueOf(s.maximumAbsoluteErrorMinutes())).orElse(""),
                        o.candidate().parameterCount(),
                        flag(va, AcceptanceReport::maeOk), flag(va, AcceptanceReport::maxErrorOk),
                        flag(va, AcceptanceReport::chouroukExactOk),
                        flag(va, AcceptanceReport::obligatoryExactOk),
                        flag(va, AcceptanceReport::noCityBiasOk),
                        flag(va, AcceptanceReport::noSeasonBiasOk),
                        flag(va, AcceptanceReport::allTargetsMet)));
            }
        }
    }

    // --- reproducibility.json -------------------------------------------------

    private void writeReproducibilityJson(Path path, SelectionResult r) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("selectedFamily", r.selectedFamily().name());
        m.put("provenance", provenanceMap(r.provenance()));
        Map<String, Object> targets = new LinkedHashMap<>();
        targets.put("maeMinutesMax", AcceptanceReport.MAE_TARGET);
        targets.put("perPrayerMaxErrorMinutes", AcceptanceReport.MAX_ERROR_TARGET);
        targets.put("chouroukExactPercentMin", AcceptanceReport.EXACT_TARGET_PERCENT);
        targets.put("obligatoryExactPercentMin", AcceptanceReport.EXACT_TARGET_PERCENT);
        targets.put("cityBiasMinutesMax", AcceptanceReport.CITY_BIAS_TARGET);
        targets.put("seasonBiasMinutesMax", AcceptanceReport.SEASON_BIAS_TARGET);
        m.put("acceptanceTargets", targets);
        m.put("holdoutEvaluated", r.holdoutResult().isPresent());
        writeJson(path, m);
    }

    // --- helpers -------------------------------------------------------------

    private static Map<String, Object> provenanceMap(Provenance p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("parametersFrozenAt", p.parametersFrozenAt());
        m.put("trainingDatasetChecksum", p.trainingDatasetChecksum());
        m.put("validationDatasetChecksum", p.validationDatasetChecksum());
        m.put("holdoutDatasetChecksum", p.holdoutDatasetChecksum());
        m.put("codeCommit", p.codeCommit());
        m.put("trainingCities", p.trainingCities());
        m.put("validationCities", p.validationCities());
        m.put("holdoutCities", p.holdoutCities());
        return m;
    }

    private static String flag(Optional<AcceptanceReport> a, java.util.function.Predicate<AcceptanceReport> f) {
        return a.map(x -> String.valueOf(f.test(x))).orElse("");
    }

    private void writeJson(Path path, Object value) throws IOException {
        try (Writer w = newWriter(path)) {
            GSON.toJson(value, w);
            w.write("\n");
        }
    }

    private static Writer newWriter(Path path) throws IOException {
        return Files.newBufferedWriter(path, StandardCharsets.UTF_8);
    }

    private static void safeWrite(Writer w, String s) {
        try {
            w.write(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String row(String key, String value) {
        return "| " + key + " | " + value + " |\n";
    }

    private static String targetRow(String target, String value, boolean met) {
        return "| " + target + " | " + value + " | " + (met ? "✅" : "❌") + " |\n";
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
