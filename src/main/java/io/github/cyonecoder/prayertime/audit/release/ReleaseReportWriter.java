package io.github.cyonecoder.prayertime.audit.release;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.cyonecoder.prayertime.audit.model.CandidateSerialization;
import io.github.cyonecoder.prayertime.audit.model.ResidualGroup;
import io.github.cyonecoder.prayertime.audit.release.HabousMethodValidator.Comparison;
import io.github.cyonecoder.prayertime.audit.release.HabousMethodValidator.Group;
import io.github.cyonecoder.prayertime.audit.release.HabousMethodValidator.GroupResult;
import io.github.cyonecoder.prayertime.audit.release.HabousMethodValidator.ValidationResult;
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

/**
 * Writes the seven Prompt-6 release deliverables into a report directory:
 *
 * <pre>
 * build/reports/habous-release/
 * ├── formula-only-summary.md
 * ├── corrected-summary.md
 * ├── official-compatible-summary.md
 * ├── all-differences.csv
 * ├── unresolved-differences.csv
 * ├── release-decision.json
 * └── reproducibility.json
 * </pre>
 *
 * <p>The three group summaries are always emitted separately; they are never
 * merged into a single unnamed result. All numeric formatting uses {@link
 * Locale#ROOT} for byte-stable output.
 */
public final class ReleaseReportWriter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** The seven files this writer always produces (relative to the output dir). */
    public static final List<String> FILES = List.of(
            "formula-only-summary.md", "corrected-summary.md", "official-compatible-summary.md",
            "all-differences.csv", "unresolved-differences.csv",
            "release-decision.json", "reproducibility.json");

    /** Provenance recorded alongside the release reports. */
    public record Provenance(
            String parametersFrozenAt, String codeCommit,
            String correctionsResourceNote, String overridesResourceNote) {
    }

    public Path write(
            Path outputDir, ValidationResult result, ReleaseDecision decision, Provenance provenance) {
        Objects.requireNonNull(outputDir, "outputDir");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(decision, "decision");
        try {
            Files.createDirectories(outputDir);
            writeGroupSummary(outputDir.resolve("formula-only-summary.md"),
                    result, result.group(Group.FORMULA_ONLY));
            writeGroupSummary(outputDir.resolve("corrected-summary.md"),
                    result, result.group(Group.FORMULA_PLUS_CORRECTIONS));
            writeGroupSummary(outputDir.resolve("official-compatible-summary.md"),
                    result, result.group(Group.FORMULA_PLUS_CORRECTIONS_PLUS_OVERRIDES));
            writeAllDifferences(outputDir.resolve("all-differences.csv"), result);
            writeUnresolvedDifferences(outputDir.resolve("unresolved-differences.csv"), result);
            writeReleaseDecision(outputDir.resolve("release-decision.json"), result, decision);
            writeReproducibility(outputDir.resolve("reproducibility.json"), result, provenance);
            return outputDir;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write release report to " + outputDir, e);
        }
    }

    // --- Group summaries ------------------------------------------------------

    private void writeGroupSummary(Path path, ValidationResult result, GroupResult g)
            throws IOException {
        try (Writer w = writer(path)) {
            w.write("# " + g.group().name() + "\n\n");
            w.write("_" + g.group().label() + "_\n\n");
            w.write("| Metric | Value |\n|---|---|\n");
            w.write(kv("exact-minute %", pct(g.exactPercentage())));
            w.write(kv("within-one-minute %", pct(g.withinOnePercentage())));
            w.write(kv("mean signed error (min)", num(g.meanSigned())));
            w.write(kv("mean absolute error (min)", num(g.meanAbsolute())));
            w.write(kv("maximum error (min)", String.valueOf(g.maxAbsolute())));
            w.write(kv("comparisons", String.valueOf(g.total())));
            w.write(kv("corrections applied", String.valueOf(g.correctionsApplied())));
            w.write(kv("overrides applied", String.valueOf(g.overridesApplied())));
            w.write(kv("unmatched records", String.valueOf(g.unmatchedRecords())));
            w.write(kv("cities included", String.valueOf(result.includedCities().size())));
            w.write(kv("missing dates", String.valueOf(result.missingDates())));

            axis(w, "Errors by prayer", g.byPrayer(), Prayer::name);
            axis(w, "Errors by city", g.byCity(), s -> s);
            axis(w, "Errors by month", g.byMonth(), YearMonth::toString);
            axis(w, "Errors by timezone offset (h)", g.byTimezoneOffset(), String::valueOf);
        }
    }

    private <K> void axis(Writer w, String title, Map<K, ResidualGroup> groups,
            java.util.function.Function<K, String> label) throws IOException {
        w.write("\n## " + title + "\n\n");
        w.write("| key | count | meanSigned | meanAbs | maxAbs |\n|---|---|---|---|---|\n");
        for (Map.Entry<K, ResidualGroup> e : groups.entrySet()) {
            ResidualGroup rg = e.getValue();
            w.write(String.format(Locale.ROOT, "| %s | %d | %+.3f | %.3f | %d |\n",
                    label.apply(e.getKey()), rg.count(), rg.meanSigned(),
                    rg.meanAbsolute(), rg.maxAbsolute()));
        }
    }

    // --- Differences CSVs -----------------------------------------------------

    private void writeAllDifferences(Path path, ValidationResult result) throws IOException {
        try (Writer w = writer(path)) {
            w.write("group,city,date,prayer,computedMinuteOfDay,officialMinuteOfDay,"
                    + "signedErrorMinutes,source\n");
            for (Group group : Group.values()) {
                for (Comparison c : result.group(group).comparisons()) {
                    w.write(String.format(Locale.ROOT, "%s,%s,%s,%s,%d,%d,%d,%s\n",
                            group.name(), c.city(), c.date(), c.prayer(),
                            c.computedMinuteOfDay(), c.officialMinuteOfDay(),
                            c.signedError(), c.source()));
                }
            }
        }
    }

    private void writeUnresolvedDifferences(Path path, ValidationResult result) throws IOException {
        // The residuals that remain in the fully-corrected (official-compatible)
        // group are the differences that block the Habous-compatible release.
        GroupResult official = result.group(Group.FORMULA_PLUS_CORRECTIONS_PLUS_OVERRIDES);
        try (Writer w = writer(path)) {
            w.write("city,date,prayer,computedMinuteOfDay,officialMinuteOfDay,"
                    + "signedErrorMinutes,source\n");
            for (Comparison c : official.comparisons()) {
                if (c.signedError() != 0) {
                    w.write(String.format(Locale.ROOT, "%s,%s,%s,%d,%d,%d,%s\n",
                            c.city(), c.date(), c.prayer(),
                            c.computedMinuteOfDay(), c.officialMinuteOfDay(),
                            c.signedError(), c.source()));
                }
            }
        }
    }

    // --- JSON -----------------------------------------------------------------

    private void writeReleaseDecision(Path path, ValidationResult result, ReleaseDecision d)
            throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("releaseApproved", d.releaseApproved());
        m.put("decision", d.releaseApproved() ? "GO" : "NO-GO");
        m.put("pureFormulaReaches100PercentExact", d.pureFormulaReaches100());
        if (!d.pureFormulaReaches100()) {
            m.put("pureFormulaStatement",
                    "The pure global formula cannot reach 100% exact-minute agreement with the "
                    + "official Habous schedule on the validated data; it reaches "
                    + String.format(Locale.ROOT, "%.2f%%", d.reconstructedExactPercentage()) + ".");
        }

        Map<String, Object> recon = new LinkedHashMap<>();
        recon.put("pass", d.reconstructedMethodPass());
        recon.put("exactPercentage", d.reconstructedExactPercentage());
        recon.put("withinOnePercentage", d.reconstructedWithinOnePercentage());
        recon.put("worstCityMeanBiasMinutes", d.reconstructedWorstCityBias());
        recon.put("requiredExactPercentage", ReleaseDecision.RECONSTRUCTED_EXACT_MIN);
        recon.put("failures", d.reconstructedFailures());
        m.put("reconstructedCalculationMethod", recon);

        Map<String, Object> official = new LinkedHashMap<>();
        official.put("pass", d.habousCompatiblePass());
        official.put("exactPercentage", d.habousCompatibleExactPercentage());
        official.put("unmatchedObservations", d.habousCompatibleUnmatched());
        official.put("unreviewedCorrections", d.unreviewedCorrections());
        official.put("unreviewedOverrides", d.unreviewedOverrides());
        official.put("staleCorrections", d.staleCorrections());
        official.put("staleOverrides", d.staleOverrides());
        official.put("missingDates", d.missingDates());
        official.put("failures", d.habousCompatibleFailures());
        m.put("habousCompatiblePublishedMode", official);

        writeJson(path, m);
    }

    private void writeReproducibility(Path path, ValidationResult result, Provenance p)
            throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("methodId", result.methodId());
        m.put("method", CandidateSerialization.toDto(result.method()));
        m.put("parametersFrozenAt", p.parametersFrozenAt());
        m.put("codeCommit", p.codeCommit());
        m.put("includedCities", result.includedCities());
        m.put("missingMappedCities", result.missingMappedCities());
        m.put("perCityDatasetChecksums", result.cityChecksums());
        m.put("correctionsResourceNote", p.correctionsResourceNote());
        m.put("overridesResourceNote", p.overridesResourceNote());
        writeJson(path, m);
    }

    // --- Helpers --------------------------------------------------------------

    private void writeJson(Path path, Object value) throws IOException {
        try (Writer w = writer(path)) {
            GSON.toJson(value, w);
            w.write("\n");
        }
    }

    private static Writer writer(Path path) throws IOException {
        return Files.newBufferedWriter(path, StandardCharsets.UTF_8);
    }

    private static String kv(String k, String v) {
        return "| " + k + " | " + v + " |\n";
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.2f%%", v);
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%+.3f", v);
    }
}
