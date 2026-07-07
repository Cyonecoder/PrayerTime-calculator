package io.github.cyonecoder.prayertime.audit.model;

import io.github.cyonecoder.prayertime.audit.dataset.HabousDatasetSplit;
import io.github.cyonecoder.prayertime.audit.model.GlobalModelSelection.FamilyOutcome;
import io.github.cyonecoder.prayertime.audit.model.GlobalModelSelection.SelectionResult;
import io.github.cyonecoder.prayertime.audit.optimization.CityLocations;
import io.github.cyonecoder.prayertime.audit.optimization.HabousRawEngine;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code research infer-global-model} verb: fits every global model family,
 * selects one on validation, freezes it, scores the holdout once, and writes the
 * eight-file report. It never activates the selected model or mutates production
 * defaults.
 */
public final class GlobalModelCommand {

    private static final Path DEFAULT_OUTPUT = Path.of("build/reports/habous-global-model");

    private final CityLocations locations;
    private final HabousRawEngine engine;

    public GlobalModelCommand() {
        this(new CityLocations(), new HabousRawEngine());
    }

    public GlobalModelCommand(CityLocations locations, HabousRawEngine engine) {
        this.locations = locations;
        this.engine = engine;
    }

    /**
     * Runs the inference.
     *
     * @param flags parsed CLI flags ({@code --dataset}, optional {@code --output})
     * @param out   the normal output stream
     */
    public void infer(Map<String, String> flags, PrintStream out) {
        Path datasetDir = Path.of(require(flags, "dataset"));
        Path output = flags.containsKey("output") ? Path.of(flags.get("output")) : DEFAULT_OUTPUT;

        ReferenceDataset full = ReferenceDataset.load(datasetDir);
        HabousDatasetSplit split = HabousDatasetSplit.load();
        GlobalModelSelection selection = new GlobalModelSelection(engine, locations, split);

        // Freeze timestamp is captured inside run(), after fitting+selection and
        // before the holdout is read.
        SelectionResult result = selection.run(full, Instant.now(), resolveCommit());
        Path written = new GlobalModelReportWriter().write(output, result);

        out.println("Global model inference complete.");
        out.println("Selected family: " + result.selectedFamily().name()
                + " (" + result.selectedFamily().label() + ")");
        out.printf(Locale.ROOT, "Training cities: %s | Validation cities: %s | Holdout cities: %s%n",
                result.provenance().trainingCities(), result.provenance().validationCities(),
                result.provenance().holdoutCities());
        for (FamilyOutcome o : result.outcomes()) {
            double vExact = o.validationResult()
                    .map(v -> v.score().exactPercentage()).orElse(Double.NaN);
            out.printf(Locale.ROOT, "  %-42s train=%.1f%% val=%s%s%s%n",
                    o.family().name(),
                    o.trainingResult().score().exactPercentage(),
                    Double.isNaN(vExact) ? "n/a" : String.format(Locale.ROOT, "%.1f%%", vExact),
                    o.family() == result.selectedFamily() ? "  <= SELECTED" : "",
                    o.finalist() ? "  [finalist]" : "");
        }
        out.println("Parameters frozen at: " + result.provenance().parametersFrozenAt());
        out.println("Report written to " + written);
    }

    private static String resolveCommit() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true).start();
            String commit = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            return p.waitFor() == 0 && !commit.isEmpty() ? commit : "unknown";
        } catch (Exception e) {
            return "unknown";
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
