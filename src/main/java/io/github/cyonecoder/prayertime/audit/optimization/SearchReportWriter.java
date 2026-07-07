package io.github.cyonecoder.prayertime.audit.optimization;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.cyonecoder.prayertime.audit.optimization.ParameterSearchService.ExactMatchInterval;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Writes the audit output files into a report directory:
 *
 * <ul>
 *   <li>{@code all-candidates.csv} — every evaluated candidate, ranked.</li>
 *   <li>{@code top-candidates.json} — the top-N candidates with full scores.</li>
 *   <li>{@code top-candidates.md} — a readable top-N table.</li>
 *   <li>{@code exact-match-intervals.csv} — equivalent parameter intervals.</li>
 *   <li>{@code residual-distribution.csv} — signed-error histogram of the best.</li>
 *   <li>{@code search-metadata.json} — dataset, ranges and run metadata.</li>
 * </ul>
 *
 * <p>All numeric formatting uses {@link Locale#ROOT} so output is byte-stable
 * across machines.
 */
public final class SearchReportWriter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * The report payload.
     *
     * @param title     a short human title for the run
     * @param ranked    all candidates, already ranked best-first
     * @param intervals equivalent-parameter intervals (may be empty)
     * @param metadata  ordered metadata for {@code search-metadata.json}
     * @param topN      how many candidates to include in the top-N outputs
     */
    public record ReportData(
            String title,
            List<CandidateResult> ranked,
            List<ExactMatchInterval> intervals,
            Map<String, Object> metadata,
            int topN) {
    }

    /**
     * Writes all six report files into {@code outputDir}, creating it if needed.
     *
     * @param outputDir the report directory
     * @param data      the report payload
     * @return the output directory
     */
    public Path write(Path outputDir, ReportData data) {
        Objects.requireNonNull(outputDir, "outputDir");
        Objects.requireNonNull(data, "data");
        try {
            Files.createDirectories(outputDir);
            writeAllCandidatesCsv(outputDir.resolve("all-candidates.csv"), data.ranked());
            writeTopCandidatesJson(outputDir.resolve("top-candidates.json"), data.ranked(), data.topN());
            writeTopCandidatesMd(outputDir.resolve("top-candidates.md"), data);
            writeIntervalsCsv(outputDir.resolve("exact-match-intervals.csv"), data.intervals());
            writeResidualDistributionCsv(outputDir.resolve("residual-distribution.csv"), data.ranked());
            writeMetadataJson(outputDir.resolve("search-metadata.json"), data.metadata());
            return outputDir;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write report to " + outputDir, e);
        }
    }

    // --- Individual files -----------------------------------------------------

    private void writeAllCandidatesCsv(Path path, List<CandidateResult> ranked) throws IOException {
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("rank,fajrAngle,ishaAngle,asrShadowFactor,sunriseAltitude,maghribAltitude,"
                    + "roundingPolicy,operationOrder,exactMatches,total,exactPercentage,"
                    + "meanSignedErrorMinutes,meanAbsoluteErrorMinutes,maximumAbsoluteErrorMinutes,"
                    + "errorStandardDeviation,cityCount,dateCount,prayerCount,parameterCount,"
                    + "crossCityStability\n");
            int rank = 1;
            for (CandidateResult r : ranked) {
                CalculationCandidate c = r.candidate();
                CandidateScore s = r.score();
                w.write(String.format(Locale.ROOT,
                        "%d,%.4f,%.4f,%.5f,%.4f,%.4f,%s,%s,%d,%d,%.4f,%.4f,%.4f,%d,%.4f,%d,%d,%d,%d,%.4f\n",
                        rank++, c.fajrAngle(), c.ishaAngle(), c.asrShadowFactor(),
                        c.sunriseAltitudeDegrees(), c.maghribAltitudeDegrees(),
                        c.roundingPolicy(), c.operationOrder(),
                        s.exactMinuteMatches(), s.total(), s.exactPercentage(),
                        s.meanSignedErrorMinutes(), s.meanAbsoluteErrorMinutes(),
                        s.maximumAbsoluteErrorMinutes(), s.errorStandardDeviation(),
                        s.cityCount(), s.dateCount(), s.prayerCount(), s.parameterCount(),
                        s.crossCityStability()));
            }
        }
    }

    private void writeTopCandidatesJson(Path path, List<CandidateResult> ranked, int topN)
            throws IOException {
        List<Map<String, Object>> top = new ArrayList<>();
        int limit = Math.min(topN, ranked.size());
        for (int i = 0; i < limit; i++) {
            CandidateResult r = ranked.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("rank", i + 1);
            entry.put("candidate", candidateMap(r.candidate()));
            entry.put("score", scoreMap(r.score()));
            top.add(entry);
        }
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(top, w);
            w.write("\n");
        }
    }

    private void writeTopCandidatesMd(Path path, ReportData data) throws IOException {
        List<CandidateResult> ranked = data.ranked();
        int limit = Math.min(data.topN(), ranked.size());
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("# " + data.title() + "\n\n");
            w.write("Top " + limit + " of " + ranked.size() + " candidates.\n\n");
            w.write("| # | sunriseAlt | maghribAlt | fajr | isha | asr | rounding | order "
                    + "| exact% | maxErr | MAE | stdDev | params |\n");
            w.write("|---|-----------|-----------|------|------|-----|----------|-------"
                    + "|--------|--------|-----|--------|--------|\n");
            for (int i = 0; i < limit; i++) {
                CandidateResult r = ranked.get(i);
                CalculationCandidate c = r.candidate();
                CandidateScore s = r.score();
                w.write(String.format(Locale.ROOT,
                        "| %d | %.4f | %.4f | %.3f | %.3f | %.4f | %s | %s | %.1f | %d | %.3f | %.3f | %d |\n",
                        i + 1, c.sunriseAltitudeDegrees(), c.maghribAltitudeDegrees(),
                        c.fajrAngle(), c.ishaAngle(), c.asrShadowFactor(),
                        c.roundingPolicy(), c.operationOrder(), s.exactPercentage(),
                        s.maximumAbsoluteErrorMinutes(), s.meanAbsoluteErrorMinutes(),
                        s.errorStandardDeviation(), s.parameterCount()));
            }
        }
    }

    private void writeIntervalsCsv(Path path, List<ExactMatchInterval> intervals) throws IOException {
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("parameter,fromValue,toValue,width,gridPoints,exactMatches,total,exactPercentage\n");
            for (ExactMatchInterval iv : intervals) {
                w.write(String.format(Locale.ROOT,
                        "%s,%.4f,%.4f,%.4f,%d,%d,%d,%.4f\n",
                        iv.parameter(), iv.fromValue(), iv.toValue(), iv.width(),
                        iv.gridPoints(), iv.exactMatches(), iv.total(), iv.exactPercentage()));
            }
        }
    }

    private void writeResidualDistributionCsv(Path path, List<CandidateResult> ranked)
            throws IOException {
        // Histogram of signed minute errors for the best candidate.
        Map<Integer, Integer> histogram = new TreeMap<>();
        if (!ranked.isEmpty()) {
            for (CandidateResult.Residual r : ranked.get(0).residuals()) {
                histogram.merge(r.signedErrorMinutes(), 1, Integer::sum);
            }
        }
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("signedErrorMinutes,count\n");
            for (Map.Entry<Integer, Integer> e : histogram.entrySet()) {
                w.write(e.getKey() + "," + e.getValue() + "\n");
            }
        }
    }

    private void writeMetadataJson(Path path, Map<String, Object> metadata) throws IOException {
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(metadata, w);
            w.write("\n");
        }
    }

    // --- Shared JSON shaping --------------------------------------------------

    private static Map<String, Object> candidateMap(CalculationCandidate c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fajrAngle", c.fajrAngle());
        m.put("ishaAngle", c.ishaAngle());
        m.put("asrShadowFactor", c.asrShadowFactor());
        m.put("sunriseAltitudeDegrees", c.sunriseAltitudeDegrees());
        m.put("maghribAltitudeDegrees", c.maghribAltitudeDegrees());
        m.put("fajrAdjustmentSeconds", c.fajrAdjustmentSeconds());
        m.put("sunriseAdjustmentSeconds", c.sunriseAdjustmentSeconds());
        m.put("dhuhrAdjustmentSeconds", c.dhuhrAdjustmentSeconds());
        m.put("asrAdjustmentSeconds", c.asrAdjustmentSeconds());
        m.put("maghribAdjustmentSeconds", c.maghribAdjustmentSeconds());
        m.put("ishaAdjustmentSeconds", c.ishaAdjustmentSeconds());
        m.put("roundingPolicy", c.roundingPolicy().name());
        m.put("operationOrder", c.operationOrder().name());
        m.put("parameterCount", c.parameterCount());
        return m;
    }

    private static Map<String, Object> scoreMap(CandidateScore s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exactMatches", s.exactMinuteMatches());
        m.put("withinOneMinuteMatches", s.withinOneMinuteMatches());
        m.put("total", s.total());
        m.put("exactPercentage", s.exactPercentage());
        m.put("meanSignedErrorMinutes", s.meanSignedErrorMinutes());
        m.put("meanAbsoluteErrorMinutes", s.meanAbsoluteErrorMinutes());
        m.put("maximumAbsoluteErrorMinutes", s.maximumAbsoluteErrorMinutes());
        m.put("errorStandardDeviation", s.errorStandardDeviation());
        m.put("cityCount", s.cityCount());
        m.put("dateCount", s.dateCount());
        m.put("prayerCount", s.prayerCount());
        m.put("parameterCount", s.parameterCount());
        m.put("crossCityStability", s.crossCityStability());
        return m;
    }
}
