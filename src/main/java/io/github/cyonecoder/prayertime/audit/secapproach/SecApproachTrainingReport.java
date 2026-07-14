package io.github.cyonecoder.prayertime.audit.secapproach;

import com.google.gson.GsonBuilder;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachTrainer.CityResult;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachTrainer.Outcome;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachTrainer.PrayerResult;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
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

/**
 * Writes the two deterministic outputs of a training run: the production-shaped
 * offsets file (loadable by {@code SecondsApproachPrayerTimesProvider}) and a rich
 * per-city, per-prayer metrics report (counts, percentages, MAE, max error, signed
 * mean, error histogram, boundary flags). Both are byte-for-byte reproducible.
 */
public final class SecApproachTrainingReport {

    /** The reviewed parameter-resource version these offsets belong to. */
    public static final String PARAMETER_VERSION = "morocco-habous-secaproach-v2";
    /** The trainer/generator version stamped into generated parameters. */
    public static final String GENERATOR_VERSION = "secapproach-trainer-v1";

    /** Provenance of the dataset a parameter file was generated from. */
    public record GeneratedFrom(
            String firstDate,
            String lastDate,
            String datasetChecksum,
            int verifiedCityCount,
            int trainedCityCount) {
    }

    private static final Prayer[] ORDER = {
        Prayer.FAJR, Prayer.SUNRISE, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA
    };
    private static final String[] KEYS = {"fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha"};

    /**
     * Writes the offsets file in the committed sec-approach schema (v2), with
     * generator and dataset provenance. City entries are ordered by canonical slug.
     */
    public void writeOffsets(Path file, Outcome outcome, GeneratedFrom generatedFrom) {
        Map<String, Map<Prayer, Integer>> offsets = outcome.offsets();
        try {
            createParent(file);
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write("{\n");
                w.write("  \"parameterVersion\": \"" + PARAMETER_VERSION + "\",\n");
                w.write("  \"model\": \"publishedMinute = floor((rawSeconds + "
                        + "offsetSeconds[city][prayer]) / 60)\",\n");
                w.write("  \"generatorVersion\": \"" + GENERATOR_VERSION + "\",\n");
                w.write("  \"generatedFrom\": {\n");
                w.write("    \"firstDate\": \"" + generatedFrom.firstDate() + "\",\n");
                w.write("    \"lastDate\": \"" + generatedFrom.lastDate() + "\",\n");
                w.write("    \"datasetChecksum\": \"" + generatedFrom.datasetChecksum() + "\",\n");
                w.write("    \"verifiedCityCount\": " + generatedFrom.verifiedCityCount() + ",\n");
                w.write("    \"trainedCityCount\": " + generatedFrom.trainedCityCount() + "\n");
                w.write("  },\n");
                w.write("  \"note\": \"Sec-approach: per-city, per-prayer CONSTANT seconds offset "
                        + "added to the reconstructed raw astronomical time, then floored to the "
                        + "minute. Trained deterministically by SecApproachTrainer (exhaustive "
                        + "integer search in [" + outcome.low() + "," + outcome.high() + "]s, "
                        + "lexicographic tie-break, min-exact=" + pct(outcome.minExact())
                        + "). A city-level calculation parameter, NOT a per-date override.\",\n");
                w.write("  \"offsets\": {\n");
                int i = 0;
                for (Map.Entry<String, Map<Prayer, Integer>> e : offsets.entrySet()) {
                    w.write("    \"" + e.getKey() + "\": " + offsetsObject(e.getValue()));
                    w.write(++i < offsets.size() ? ",\n" : "\n");
                }
                w.write("  }\n}\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("writing offsets " + file, e);
        }
    }

    /** Writes the detailed metrics report. */
    public void writeReport(Path file, Outcome outcome) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("note", "Deterministic sec-approach training report. Per-city, per-prayer "
                + "discrete integer-offset search; the same dataset reproduces this byte-for-byte.");
        root.put("searchLow", outcome.low());
        root.put("searchHigh", outcome.high());
        root.put("minExact", outcome.minExact());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("preserved", outcome.preserved().size());
        summary.put("written", outcome.written().size());
        summary.put("belowBand", outcome.belowBand().size());
        summary.put("unresolved", outcome.unresolved().size());
        summary.put("boundaryTouches", outcome.boundaryTouches().stream().map(CityResult::city).toList());
        root.put("summary", summary);

        List<Map<String, Object>> cities = new ArrayList<>();
        outcome.preserved().forEach(c -> cities.add(cityJson(c, "PRESERVED", outcome)));
        outcome.written().forEach(c -> cities.add(cityJson(c, "WRITTEN", outcome)));
        outcome.belowBand().forEach(c -> cities.add(cityJson(c, "BELOW_BAND", outcome)));
        root.put("cities", cities);

        try {
            createParent(file);
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root, w);
                w.write("\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("writing report " + file, e);
        }
    }

    /**
     * Writes the honest, overfitting-aware holdout report for the newly trained
     * cities: chronological training/validation/holdout metrics (out-of-sample)
     * alongside the shipped in-sample offset, with an explicit not-annual caveat.
     */
    public void writeHoldoutReport(
            Path file,
            List<SecApproachSplitEvaluator.CitySplitEvaluation> evaluations,
            int low, int high) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("note", "Honest, overfitting-aware evaluation of the newly trained cities. "
                + "The offset is fit on the TRAINING segment only, ties are broken on VALIDATION, "
                + "and the selected offset is evaluated UNTOUCHED on HOLDOUT. Holdout figures are "
                + "the out-of-sample estimate; the shipped offset is refit on all dates and its "
                + "full-dataset figures are labelled inSample.");
        root.put("caveat", "This dataset is a single 30-day SUMMER window (2026-06-17..2026-07-16). "
                + "These figures are NOT a claim of annual or year-round accuracy.");
        Map<String, Object> split = new LinkedHashMap<>();
        split.put("rule", "first two-thirds training, next one-sixth validation, final one-sixth holdout");
        split.put("training", "2026-06-17..2026-07-06 (20 days)");
        split.put("validation", "2026-07-07..2026-07-11 (5 days)");
        split.put("holdout", "2026-07-12..2026-07-16 (5 days)");
        root.put("split", split);
        root.put("searchLow", low);
        root.put("searchHigh", high);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("cities", evaluations.size());
        summary.put("meanTrainingExactPercentage", round2(mean(evaluations, e -> e.trainingExactPercentage())));
        summary.put("meanValidationExactPercentage", round2(mean(evaluations, e -> e.validationExactPercentage())));
        summary.put("meanHoldoutExactPercentageOutOfSample",
                round2(mean(evaluations, e -> e.holdoutExactPercentage())));
        summary.put("meanInSampleExactPercentage", round2(mean(evaluations, e -> e.inSampleExactPercentage())));
        root.put("summary", summary);

        List<Map<String, Object>> cities = new ArrayList<>();
        for (SecApproachSplitEvaluator.CitySplitEvaluation e : evaluations) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("city", e.city());
            cm.put("trainingExactPercentage", round2(e.trainingExactPercentage()));
            cm.put("validationExactPercentage", round2(e.validationExactPercentage()));
            cm.put("holdoutExactPercentageOutOfSample", round2(e.holdoutExactPercentage()));
            cm.put("inSampleExactPercentage", round2(e.inSampleExactPercentage()));
            List<Map<String, Object>> prayers = new ArrayList<>();
            for (SecApproachSplitEvaluator.PrayerSplitEvaluation p : e.prayers()) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("prayer", p.prayer().name().toLowerCase(Locale.ROOT));
                pm.put("splitOffset", p.splitOffset());
                pm.put("releaseOffset", p.releaseOffset());
                pm.put("training", segment(p.training()));
                pm.put("validation", segment(p.validation()));
                pm.put("holdoutOutOfSample", segment(p.holdout()));
                pm.put("inSample", segment(p.inSample()));
                prayers.add(pm);
            }
            cm.put("prayers", prayers);
            cities.add(cm);
        }
        root.put("cities", cities);
        writeJsonTree(file, root);
    }

    private static Map<String, Object> segment(SecApproachMetrics m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("observations", m.observations());
        out.put("exactCount", m.exactCount());
        out.put("exactPercentage", round2(m.exactPercentage()));
        out.put("withinOneCount", m.withinOneCount());
        out.put("meanAbsoluteError", round3(m.meanAbsoluteError()));
        out.put("maxAbsoluteError", m.maxAbsoluteError());
        return out;
    }

    private static double mean(
            List<SecApproachSplitEvaluator.CitySplitEvaluation> evaluations,
            java.util.function.ToDoubleFunction<SecApproachSplitEvaluator.CitySplitEvaluation> f) {
        return evaluations.isEmpty() ? 0.0
                : evaluations.stream().mapToDouble(f).average().orElse(0.0);
    }

    private void writeJsonTree(Path file, Object root) {
        try {
            createParent(file);
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root, w);
                w.write("\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("writing report " + file, e);
        }
    }

    private Map<String, Object> cityJson(CityResult c, String status, Outcome outcome) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("city", c.city());
        m.put("status", status);
        m.put("snapshotChecksum", c.snapshotChecksum());
        m.put("dayCount", c.dayCount());
        m.put("exactPercentage", round2(100.0 * c.exactRatio()));
        m.put("boundaryTouched", c.touchesBoundary(outcome.low(), outcome.high()));
        List<Map<String, Object>> prayers = new ArrayList<>();
        for (PrayerResult p : c.prayers()) {
            SecApproachMetrics x = p.metrics();
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("prayer", p.prayer().name().toLowerCase(Locale.ROOT));
            pm.put("offset", p.offset());
            pm.put("fromBase", p.fromBase());
            pm.put("observations", x.observations());
            pm.put("exactCount", x.exactCount());
            pm.put("exactPercentage", round2(x.exactPercentage()));
            pm.put("withinOneCount", x.withinOneCount());
            pm.put("withinOnePercentage", round2(x.withinOnePercentage()));
            pm.put("meanAbsoluteError", round3(x.meanAbsoluteError()));
            pm.put("maxAbsoluteError", x.maxAbsoluteError());
            pm.put("signedMeanError", round3(x.signedMeanError()));
            Map<String, Integer> hist = new LinkedHashMap<>();
            x.errorHistogram().forEach((err, count) -> hist.put(Integer.toString(err), count));
            pm.put("errorHistogram", hist);
            prayers.add(pm);
        }
        m.put("prayers", prayers);
        return m;
    }

    private static String offsetsObject(Map<Prayer, Integer> offsets) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < ORDER.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append('"').append(KEYS[i]).append("\":").append(offsets.getOrDefault(ORDER[i], 0));
        }
        return sb.append('}').toString();
    }

    private static void createParent(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String pct(double ratio) {
        return String.format(Locale.ROOT, "%.0f%%", 100 * ratio);
    }
}
