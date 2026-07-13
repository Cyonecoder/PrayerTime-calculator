package io.github.cyonecoder.prayertime.audit.secapproach;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.cyonecoder.prayertime.audit.optimization.HabousRawEngine;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachTrainer.CityResult;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachTrainer.Outcome;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Handles {@code research train-secaproach}: trains the sec-approach offsets
 * deterministically from a research dataset and writes the offsets file plus a
 * metrics report.
 *
 * <pre>research train-secaproach --dataset &lt;dir&gt; [--base file] [--min-exact 0.95]
 *   [--low -900] [--high 900] [--offsets-out file] [--report-out file]</pre>
 *
 * <p>{@code --base} preserves already-shipped cities verbatim; only cities meeting
 * the exact-minute band are written. Boundary touches are reported so the search
 * window is only ever widened deliberately, never automatically.
 */
public final class SecApproachTrainingCommand {

    private static final Path DEFAULT_OFFSETS =
            Path.of("build/reports/secapproach-all-cities/morocco-habous-secaproach.json");
    private static final Path DEFAULT_REPORT =
            Path.of("build/reports/secapproach-all-cities/training-report.json");
    private static final Path DEFAULT_HOLDOUT =
            Path.of("build/reports/secapproach-all-cities/holdout-report.json");

    private final MoroccoCityRegistry registry;
    private final HabousRawEngine engine;

    public SecApproachTrainingCommand() {
        this(new MoroccoCityRegistry(), new HabousRawEngine());
    }

    public SecApproachTrainingCommand(MoroccoCityRegistry registry, HabousRawEngine engine) {
        this.registry = registry;
        this.engine = engine;
    }

    /** Runs a training invocation. */
    public void run(Map<String, String> flags, PrintStream out) {
        Path datasetDir = Path.of(require(flags, "dataset"));
        int low = intFlag(flags, "low", SecApproachOffsetSearch.DEFAULT_LOW);
        int high = intFlag(flags, "high", SecApproachOffsetSearch.DEFAULT_HIGH);
        double minExact = minExact(flags.get("min-exact"));
        Path offsetsOut = path(flags, "offsets-out", DEFAULT_OFFSETS);
        Path reportOut = path(flags, "report-out", DEFAULT_REPORT);
        Path holdoutOut = path(flags, "holdout-out", DEFAULT_HOLDOUT);
        Map<String, Map<Prayer, Integer>> base = flags.containsKey("base")
                ? loadOffsets(Path.of(flags.get("base"))) : Map.of();

        SecApproachTrainingDataset dataset =
                SecApproachTrainingDataset.fromDirectory(datasetDir, registry, engine);
        Outcome outcome = new SecApproachTrainer(low, high, minExact).train(dataset, base);

        // Honest, overfitting-aware evaluation of the newly trained cities: fit on
        // training, break ties on validation, evaluate untouched on holdout.
        SecApproachSplitEvaluator splitEvaluator = new SecApproachSplitEvaluator(low, high);
        java.util.List<SecApproachSplitEvaluator.CitySplitEvaluation> holdout =
                outcome.written().stream()
                        .map(c -> splitEvaluator.evaluate(dataset, c.city()))
                        .toList();

        SecApproachTrainingReport report = new SecApproachTrainingReport();
        report.writeOffsets(offsetsOut, outcome);
        report.writeReport(reportOut, outcome);
        report.writeHoldoutReport(holdoutOut, holdout, low, high);

        out.printf(Locale.ROOT, "Trained sec-approach from %s (search [%d,%d]s, min-exact=%.0f%%)%n",
                datasetDir, low, high, 100 * minExact);
        outcome.preserved().forEach(c ->
                out.printf(Locale.ROOT, "  %-14s PRESERVED (exact=%.2f%%)%n",
                        c.city(), 100 * c.exactRatio()));
        outcome.written().forEach(c ->
                out.printf(Locale.ROOT, "  %-14s WRITTEN   exact=%.2f%%%s%n",
                        c.city(), 100 * c.exactRatio(),
                        c.touchesBoundary(low, high) ? "  [BOUNDARY]" : ""));
        outcome.belowBand().forEach(c ->
                out.printf(Locale.ROOT, "  %-14s SKIPPED below band (exact=%.2f%%)%n",
                        c.city(), 100 * c.exactRatio()));
        outcome.unresolved().forEach(c ->
                out.printf(Locale.ROOT, "  %-14s SKIPPED not in city registry%n", c));
        if (!outcome.boundaryTouches().isEmpty()) {
            out.println("WARNING: offsets touched the search boundary for: "
                    + outcome.boundaryTouches().stream().map(CityResult::city).toList()
                    + " — recheck coordinates/timezone/mapping before widening the window.");
        }
        if (!holdout.isEmpty()) {
            double meanHoldout = holdout.stream()
                    .mapToDouble(SecApproachSplitEvaluator.CitySplitEvaluation::holdoutExactPercentage)
                    .average().orElse(0.0);
            double meanInSample = holdout.stream()
                    .mapToDouble(SecApproachSplitEvaluator.CitySplitEvaluation::inSampleExactPercentage)
                    .average().orElse(0.0);
            out.printf(Locale.ROOT,
                    "Honest holdout (out-of-sample) mean exact = %.2f%%  vs in-sample %.2f%% "
                            + "(30-day summer window; NOT an annual claim)%n",
                    meanHoldout, meanInSample);
        }
        out.printf(Locale.ROOT, "Wrote %d offsets -> %s%n", outcome.offsets().size(), offsetsOut);
        out.println("Report -> " + reportOut);
        out.println("Holdout report -> " + holdoutOut);
    }

    /** Loads an existing sec-approach offsets file (for {@code --base}). */
    public static Map<String, Map<Prayer, Integer>> loadOffsets(Path file) {
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("reading base offsets " + file, e);
        }
        JsonObject root = new Gson().fromJson(json, JsonObject.class);
        if (root == null || !root.has("offsets")) {
            throw new IllegalArgumentException("no offsets in base file: " + file);
        }
        Map<String, Map<Prayer, Integer>> out = new TreeMap<>();
        JsonObject offsets = root.getAsJsonObject("offsets");
        for (String city : offsets.keySet()) {
            JsonObject o = offsets.getAsJsonObject(city);
            Map<Prayer, Integer> byPrayer = new EnumMap<>(Prayer.class);
            byPrayer.put(Prayer.FAJR, o.get("fajr").getAsInt());
            byPrayer.put(Prayer.SUNRISE, o.get("sunrise").getAsInt());
            byPrayer.put(Prayer.DHUHR, o.get("dhuhr").getAsInt());
            byPrayer.put(Prayer.ASR, o.get("asr").getAsInt());
            byPrayer.put(Prayer.MAGHRIB, o.get("maghrib").getAsInt());
            byPrayer.put(Prayer.ISHA, o.get("isha").getAsInt());
            out.put(city.trim().toLowerCase(Locale.ROOT), byPrayer);
        }
        return out;
    }

    private static Path path(Map<String, String> flags, String key, Path fallback) {
        return flags.containsKey(key) ? Path.of(flags.get(key)) : fallback;
    }

    private static int intFlag(Map<String, String> flags, String key, int fallback) {
        String v = flags.get(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid --" + key + ": " + v);
        }
    }

    private static double minExact(String value) {
        if (value == null) {
            return 0.95;
        }
        try {
            double v = Double.parseDouble(value);
            if (v < 0.0 || v > 1.0) {
                throw new IllegalArgumentException("--min-exact must be in [0,1]: " + value);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid --min-exact: " + value);
        }
    }

    private static String require(Map<String, String> flags, String key) {
        String value = flags.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing required flag --" + key);
        }
        return value;
    }
}
