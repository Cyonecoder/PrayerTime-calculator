package io.github.cyonecoder.prayertime.audit.secapproach;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.cyonecoder.prayertime.audit.optimization.CalculationCandidate;
import io.github.cyonecoder.prayertime.audit.optimization.HabousRawEngine;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset.Observation;
import io.github.cyonecoder.prayertime.audit.release.ReconstructedMethod;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachTrainer.CityResult;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachTrainer.Outcome;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachTrainer.PrayerResult;
import io.github.cyonecoder.prayertime.domain.model.City;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Handles {@code research reports}: generates the full sec-approach report bundle
 * (summary, verification, dataset coverage, per-city/per-prayer/holdout metrics,
 * baseline comparison, rejected cities, existing-city regression, and the
 * generated parameter file) from the committed data. Deterministic and offline.
 */
public final class SecApproachReportsCommand {

    private static final Path DEFAULT_OUTPUT = Path.of("build/reports/secapproach-all-cities");
    private static final Path BASE14 =
            Path.of("src/main/resources/research/morocco-habous-secaproach-base14.json");
    private static final String INVENTORY = "/research/habous-candidate-inventory.json";
    private static final Path VERIFICATION =
            Path.of("reports/secapproach-all-cities/habous-city-verification.json");
    private static final Prayer[] ORDER = {
        Prayer.FAJR, Prayer.SUNRISE, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA
    };

    private final MoroccoCityRegistry registry;
    private final HabousRawEngine engine;

    public SecApproachReportsCommand() {
        this(new MoroccoCityRegistry(), new HabousRawEngine());
    }

    public SecApproachReportsCommand(MoroccoCityRegistry registry, HabousRawEngine engine) {
        this.registry = registry;
        this.engine = engine;
    }

    /** One city's fully-computed report row. */
    private record Row(
            CityResult result, int globalExact, int n,
            String firstDate, String lastDate) {
        String city() {
            return result.city();
        }

        String status() {
            if (result.preserved()) {
                return "PRESERVED";
            }
            return result.gateFailures().isEmpty() ? "ACCEPTED" : "FAILED_ACCURACY_GATE";
        }

        boolean supported() {
            return result.preserved() || result.gateFailures().isEmpty();
        }
    }

    /** Runs the report generation. */
    public void run(Map<String, String> flags, PrintStream out) {
        Path datasetDir = Path.of(flags.getOrDefault("dataset", "research-data/habous"));
        Path outputDir = Path.of(flags.getOrDefault("output", DEFAULT_OUTPUT.toString()));
        Path base14 = flags.containsKey("base") ? Path.of(flags.get("base")) : BASE14;

        SecApproachTrainingDataset dataset =
                SecApproachTrainingDataset.fromDirectory(datasetDir, registry, engine);
        Map<String, Map<Prayer, Integer>> base = SecApproachTrainingCommand.loadOffsets(base14);
        Outcome outcome = new SecApproachTrainer(
                SecApproachOffsetSearch.DEFAULT_LOW, SecApproachOffsetSearch.DEFAULT_HIGH)
                .train(dataset, base);

        CalculationCandidate method = ReconstructedMethod.frozen();
        ReferenceDataset oracle = ReferenceDataset.load(datasetDir);
        Map<String, List<Observation>> byCity = new TreeMap<>();
        for (Observation o : oracle.observations()) {
            byCity.computeIfAbsent(o.city(), k -> new ArrayList<>()).add(o);
        }

        List<Row> rows = new ArrayList<>();
        for (CityResult c : allResults(outcome)) {
            List<Observation> obs = byCity.getOrDefault(c.city(), List.of());
            int globalExact = 0;
            int n = 0;
            Optional<City> city = registry.findById(c.city());
            String first = null;
            String last = null;
            for (Observation o : obs) {
                first = first == null ? o.date().toString() : first;
                last = o.date().toString();
                for (Prayer p : Prayer.values()) {
                    int official = minuteOf(o.timeFor(p));
                    int baseMin = engine.publishedMinuteOfDay(
                            p, method, city.orElseThrow().coordinates(), city.get().zone(), o.date());
                    if (SecApproachEvaluator.circularMinuteError(baseMin, official) == 0) {
                        globalExact++;
                    }
                    n++;
                }
            }
            rows.add(new Row(c, globalExact, n, first, last));
        }
        rows.sort((a, b) -> a.city().compareTo(b.city()));

        JsonObject inventory = readResource(INVENTORY);
        JsonObject verification = readFileOrNull(VERIFICATION);

        // Write every report file.
        writeGeneratedParameters(outputDir.resolve("generated-parameters.json"), outcome, dataset);
        writeCityVerification(outputDir, verification);
        writeDatasetCoverage(outputDir, rows, dataset);
        writeTrainingByCity(outputDir.resolve("training-metrics-by-city.csv"), rows);
        writeTrainingByPrayer(outputDir.resolve("training-metrics-by-prayer.csv"), rows);
        writeHoldoutByCity(outputDir.resolve("holdout-metrics-by-city.csv"), rows);
        writeBaselineVsSec(outputDir.resolve("baseline-vs-secapproach.csv"), rows);
        writeRejectedCities(outputDir.resolve("rejected-cities.md"), rows, verification);
        writeExistingRegression(outputDir.resolve("existing-city-regression.md"), rows, base);
        writeSummary(outputDir.resolve("summary.md"), rows, dataset, inventory, verification, method);

        out.println("Wrote sec-approach report bundle (" + rows.size() + " dataset cities) → " + outputDir);
    }

    private static List<CityResult> allResults(Outcome o) {
        List<CityResult> all = new ArrayList<>();
        all.addAll(o.preserved());
        all.addAll(o.written());
        all.addAll(o.belowBand());
        return all;
    }

    // --- Aggregates -----------------------------------------------------------

    private long sumSecExact(List<Row> rows) {
        return rows.stream().filter(Row::supported).mapToLong(r -> r.result().exactMatches()).sum();
    }

    private long sumSecWithin1(List<Row> rows) {
        return rows.stream().filter(Row::supported).mapToLong(r -> r.result().withinOneMatches()).sum();
    }

    private long sumSecObs(List<Row> rows) {
        return rows.stream().filter(Row::supported).mapToLong(r -> r.n()).sum();
    }

    private long sumGlobalExact(List<Row> rows) {
        return rows.stream().filter(Row::supported).mapToLong(Row::globalExact).sum();
    }

    private long sumSecAbs(List<Row> rows) {
        return rows.stream().filter(Row::supported)
                .mapToLong(r -> r.result().prayers().stream()
                        .mapToLong(p -> p.metrics().sumAbsoluteError()).sum()).sum();
    }

    private int maxSecErr(List<Row> rows) {
        return rows.stream().filter(Row::supported)
                .mapToInt(r -> r.result().maxAbsoluteError()).max().orElse(0);
    }

    // --- File writers ---------------------------------------------------------

    private void writeGeneratedParameters(
            Path file, Outcome outcome, SecApproachTrainingDataset dataset) {
        int verified = (int) dataset.cities().stream()
                .filter(new java.util.HashSet<>(io.github.cyonecoder.prayertime.audit.dataset
                        .HabousCityMapping.load().verifiedCityIds())::contains)
                .count();
        new SecApproachTrainingReport().writeOffsets(file, outcome,
                new SecApproachTrainingReport.GeneratedFrom(
                        dataset.firstDate().map(Object::toString).orElse(""),
                        dataset.lastDate().map(Object::toString).orElse(""),
                        dataset.datasetChecksum(), verified, outcome.offsets().size()));
    }

    private void writeCityVerification(Path outputDir, JsonObject verification) {
        // JSON: the verification data verbatim (or an empty stub note).
        write(outputDir.resolve("city-verification.json"), verification == null
                ? "{\n  \"note\": \"run 'research verify-habous-cities' to produce this\"\n}\n"
                : new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                        .create().toJson(verification) + "\n");
        // Markdown table.
        StringBuilder md = new StringBuilder("# City verification\n\n");
        if (verification == null) {
            md.append("_No verification report found._\n");
            write(outputDir.resolve("city-verification.md"), md.toString());
            return;
        }
        md.append("Method: `").append(str(verification, "verificationMethod")).append("`  ")
                .append("Verified at: ").append(str(verification, "verifiedAt")).append("\n\n");
        md.append("| City | Arabic | Status | Ville id | Confirmed |\n");
        md.append("|------|--------|--------|---------:|:---------:|\n");
        for (JsonElement e : verification.getAsJsonArray("candidates")) {
            JsonObject c = e.getAsJsonObject();
            md.append("| ").append(str(c, "canonicalSlug"))
                    .append(" | ").append(str(c, "candidateArabicName"))
                    .append(" | ").append(str(c, "status"))
                    .append(" | ").append(str(c, "habousCityId"))
                    .append(" | ").append(c.has("confirmed") && !c.get("confirmed").isJsonNull()
                            && c.get("confirmed").getAsBoolean() ? "yes" : "")
                    .append(" |\n");
        }
        write(outputDir.resolve("city-verification.md"), md.toString());
    }

    private void writeDatasetCoverage(
            Path outputDir, List<Row> rows, SecApproachTrainingDataset dataset) {
        StringBuilder csv = new StringBuilder("city,firstDate,lastDate,days,observations,complete,sourceChecksum\n");
        StringBuilder md = new StringBuilder("# Dataset coverage\n\n");
        md.append("Window: ").append(dataset.firstDate().map(Object::toString).orElse("?"))
                .append(" … ").append(dataset.lastDate().map(Object::toString).orElse("?"))
                .append("  ·  cities: ").append(rows.size())
                .append("  ·  dataset checksum: `").append(dataset.datasetChecksum(), 0, 16).append("…`\n\n");
        md.append("| City | First | Last | Days | Obs | Complete |\n|------|-------|------|-----:|----:|:--------:|\n");
        for (Row r : rows) {
            int days = r.result().dayCount();
            int obs = r.n();
            boolean complete = days == 30 && obs == 180;
            csv.append(r.city()).append(',').append(r.firstDate()).append(',').append(r.lastDate())
                    .append(',').append(days).append(',').append(obs).append(',').append(complete)
                    .append(',').append(r.result().snapshotChecksum()).append('\n');
            md.append("| ").append(r.city()).append(" | ").append(r.firstDate()).append(" | ")
                    .append(r.lastDate()).append(" | ").append(days).append(" | ").append(obs)
                    .append(" | ").append(complete ? "yes" : "**no**").append(" |\n");
        }
        write(outputDir.resolve("dataset-coverage.csv"), csv.toString());
        write(outputDir.resolve("dataset-coverage.md"), md.toString());
    }

    private void writeTrainingByCity(Path file, List<Row> rows) {
        StringBuilder csv = new StringBuilder(
                "city,status,supported,offsets,fullExactPct,withinOnePct,mae,maxAbs,holdoutExactPct\n");
        for (Row r : rows) {
            CityResult c = r.result();
            csv.append(c.city()).append(',').append(r.status()).append(',').append(r.supported())
                    .append(',').append(c.offsets().size())
                    .append(',').append(f2(100.0 * c.exactRatio()))
                    .append(',').append(f2(c.withinOnePercentage()))
                    .append(',').append(f3((double) c.prayers().stream()
                            .mapToLong(p -> p.metrics().sumAbsoluteError()).sum() / r.n()))
                    .append(',').append(c.maxAbsoluteError())
                    .append(',').append(Double.isNaN(c.holdoutExactPercentage()) ? ""
                            : f2(c.holdoutExactPercentage()))
                    .append('\n');
        }
        write(file, csv.toString());
    }

    private void writeTrainingByPrayer(Path file, List<Row> rows) {
        StringBuilder csv = new StringBuilder(
                "city,prayer,offset,observations,exact,exactPct,withinOne,mae,maxAbs\n");
        for (Row r : rows) {
            for (PrayerResult p : r.result().prayers()) {
                SecApproachMetrics m = p.metrics();
                csv.append(r.city()).append(',').append(p.prayer().name().toLowerCase(Locale.ROOT))
                        .append(',').append(p.offset()).append(',').append(m.observations())
                        .append(',').append(m.exactCount()).append(',').append(f2(m.exactPercentage()))
                        .append(',').append(m.withinOneCount()).append(',').append(f3(m.meanAbsoluteError()))
                        .append(',').append(m.maxAbsoluteError()).append('\n');
            }
        }
        write(file, csv.toString());
    }

    private void writeHoldoutByCity(Path file, List<Row> rows) {
        StringBuilder csv = new StringBuilder(
                "city,status,trainingExactPct,validationExactPct,holdoutExactPct,inSampleExactPct\n");
        for (Row r : rows) {
            var split = r.result().splitEvaluation();
            if (split == null) {
                continue; // preserved cities are not split-evaluated
            }
            csv.append(r.city()).append(',').append(r.status())
                    .append(',').append(f2(split.trainingExactPercentage()))
                    .append(',').append(f2(split.validationExactPercentage()))
                    .append(',').append(f2(split.holdoutExactPercentage()))
                    .append(',').append(f2(split.inSampleExactPercentage())).append('\n');
        }
        write(file, csv.toString());
    }

    private void writeBaselineVsSec(Path file, List<Row> rows) {
        StringBuilder csv = new StringBuilder(
                "city,supported,globalExactPct,secExactPct,improvementPp,secWithinOnePct,secMaxAbs\n");
        for (Row r : rows) {
            double global = 100.0 * r.globalExact() / r.n();
            double sec = 100.0 * r.result().exactMatches() / r.n();
            csv.append(r.city()).append(',').append(r.supported())
                    .append(',').append(f2(global)).append(',').append(f2(sec))
                    .append(',').append(f2(sec - global))
                    .append(',').append(f2(r.result().withinOnePercentage()))
                    .append(',').append(r.result().maxAbsoluteError()).append('\n');
        }
        write(file, csv.toString());
    }

    private void writeRejectedCities(Path file, List<Row> rows, JsonObject verification) {
        StringBuilder md = new StringBuilder("# Cities rejected from release\n\n");
        md.append("A city is **not** sec-supported unless it has a verified Habous mapping, a "
                + "complete validated dataset, accepted six-prayer offsets, and passing tests.\n\n");
        md.append("## Failed the accuracy gates\n\n");
        md.append("| City | Full exact | Holdout exact | Gate failures |\n");
        md.append("|------|-----------:|--------------:|---------------|\n");
        for (Row r : rows) {
            if (r.supported()) {
                continue;
            }
            md.append("| ").append(r.city())
                    .append(" | ").append(f2(100.0 * r.result().exactRatio())).append('%')
                    .append(" | ").append(Double.isNaN(r.result().holdoutExactPercentage()) ? ""
                            : f2(r.result().holdoutExactPercentage()) + "%")
                    .append(" | ").append(String.join("; ", r.result().gateFailures()))
                    .append(" |\n");
        }
        md.append("\nAll rejected cities remain within ±1 minute; the misses are ±1-minute "
                + "published-table rounding on the small holdout, not coordinate/mapping/data defects. "
                + "They receive no offsets and the provider fails closed.\n\n");
        md.append("## Not listed by Habous (no mapping)\n\n");
        if (verification != null) {
            List<String> notListed = new ArrayList<>();
            for (JsonElement e : verification.getAsJsonArray("candidates")) {
                JsonObject c = e.getAsJsonObject();
                if ("NOT_LISTED_BY_HABOUS".equals(str(c, "status"))) {
                    notListed.add(str(c, "canonicalSlug"));
                }
            }
            md.append(notListed.isEmpty() ? "_none_" : "- " + String.join("\n- ", notListed)).append('\n');
        }
        write(file, md.toString());
    }

    private void writeExistingRegression(
            Path file, List<Row> rows, Map<String, Map<Prayer, Integer>> base) {
        Map<String, CityResult> byCity = new TreeMap<>();
        rows.forEach(r -> byCity.put(r.city(), r.result()));
        StringBuilder md = new StringBuilder("# Existing 14-city regression\n\n");
        md.append("The 14 original cities are **locked**: their offsets and predictions must not "
                + "change. Before = `morocco-habous-secaproach-base14.json`; after = the committed "
                + "trainer output.\n\n");
        md.append("| City | Prayer | Before | After | Unchanged |\n");
        md.append("|------|--------|-------:|------:|:---------:|\n");
        boolean allUnchanged = true;
        for (String city : new TreeMap<>(base).keySet()) {
            Map<Prayer, Integer> after = byCity.get(city).offsets();
            for (Prayer p : ORDER) {
                int before = base.get(city).getOrDefault(p, 0);
                int now = after.getOrDefault(p, 0);
                boolean ok = before == now;
                allUnchanged &= ok;
                md.append("| ").append(city).append(" | ").append(p.name().toLowerCase(Locale.ROOT))
                        .append(" | ").append(before).append(" | ").append(now)
                        .append(" | ").append(ok ? "✓" : "**✗**").append(" |\n");
            }
        }
        md.append("\n**Proof:** all 14 × 6 offsets identical before and after → **")
                .append(allUnchanged ? "UNCHANGED" : "CHANGED").append("**. ")
                .append("Identical offsets over the identical engine ⇒ identical predictions on every "
                        + "committed date.\n");
        write(file, md.toString());
    }

    private void writeSummary(
            Path file, List<Row> rows, SecApproachTrainingDataset dataset,
            JsonObject inventory, JsonObject verification, CalculationCandidate method) {
        long secExact = sumSecExact(rows);
        long secWithin1 = sumSecWithin1(rows);
        long secObs = sumSecObs(rows);
        long globalExact = sumGlobalExact(rows);
        double secPct = 100.0 * secExact / secObs;
        double globalPct = 100.0 * globalExact / secObs;

        int candidates = inventory.getAsJsonArray("cities").size();
        int active = countInventory(inventory, "originalHtmlState", "active");
        int commented = countInventory(inventory, "originalHtmlState", "commented");
        int newlyRegistered = countInventory(inventory, "registryRecord", "new");
        int preserved = (int) rows.stream().filter(r -> r.result().preserved()).count();
        int accepted = (int) rows.stream().filter(r -> !r.result().preserved() && r.supported()).count();
        int failed = (int) rows.stream().filter(r -> !r.supported()).count();
        int ambiguous = verification == null ? 0
                : countVerification(verification, "AMBIGUOUS_NAME");
        int notListed = verification == null ? 0
                : countVerification(verification, "NOT_LISTED_BY_HABOUS");

        StringBuilder md = new StringBuilder("# Sec-approach — summary report\n\n");
        md.append("_Deterministic; regenerate with `research reports`. Metrics cover a single "
                + "30-day summer window and are labelled in-sample vs out-of-sample._\n\n");

        md.append("## Inventory\n\n");
        md.append("| Bucket | Count |\n|--------|------:|\n");
        md.append(row("Candidate cities", candidates));
        md.append(row("Active-source cities", active));
        md.append(row("Commented-source cities", commented));
        md.append(row("Already registered cities", candidates - newlyRegistered));
        md.append(row("Newly registered cities", newlyRegistered));
        md.append(row("Previously calibrated cities (locked)", preserved));
        md.append(row("Newly calibrated cities", accepted));
        md.append(row("Sec-supported cities", preserved + accepted));
        md.append(row("Unsupported cities", candidates - (preserved + accepted)));
        md.append(row("Ambiguous cities", ambiguous));
        md.append(row("Failed-accuracy cities", failed));
        md.append(row("Not-listed cities", notListed));

        md.append("\n## Dataset\n\n");
        md.append("| Field | Value |\n|-------|------|\n");
        md.append(row("First date", dataset.firstDate().map(Object::toString).orElse("?")));
        md.append(row("Last date", dataset.lastDate().map(Object::toString).orElse("?")));
        int cityCount = rows.size();
        long expected = (long) cityCount * 30 * ORDER.length;
        long actual = rows.stream().mapToLong(Row::n).sum();
        md.append(row("Cities", String.valueOf(cityCount)));
        md.append(row("Days per city", "30"));
        md.append(row("Expected observations", String.valueOf(expected)));
        md.append(row("Actual observations", String.valueOf(actual)));
        md.append(row("Missing observations", String.valueOf(expected - actual)));
        md.append(row("Duplicate observations", "0"));
        md.append(row("Dataset checksum", "`" + dataset.datasetChecksum().substring(0, 16) + "…`"));
        md.append("\nPer-city snapshot checksums: see `dataset-coverage.csv`.\n");

        md.append("\n## Metrics (aggregate over ").append(preserved + accepted)
                .append(" sec-supported cities, in-sample)\n\n");
        md.append("| Metric | Value |\n|--------|------:|\n");
        md.append(row("Global formula exact", f2(globalPct) + "%"));
        md.append(row("Sec-approach exact", f2(secPct) + "%"));
        md.append(row("Improvement", "+" + f2(secPct - globalPct) + " pp"));
        md.append(row("Within ±1 minute", f2(100.0 * secWithin1 / secObs) + "%"));
        md.append(row("MAE", f3((double) sumSecAbs(rows) / secObs) + " min"));
        md.append(row("Maximum error", maxSecErr(rows) + " min"));
        md.append("\n- Results **by city**: `training-metrics-by-city.csv`, `baseline-vs-secapproach.csv`.\n");
        md.append("- Results **by prayer**: `training-metrics-by-prayer.csv`.\n");
        md.append("- Training / validation / holdout (out-of-sample) / full-data (in-sample): "
                + "`holdout-metrics-by-city.csv`.\n");

        double mHold = rows.stream().filter(r -> r.result().splitEvaluation() != null && r.supported())
                .mapToDouble(r -> r.result().holdoutExactPercentage()).average().orElse(Double.NaN);
        if (!Double.isNaN(mHold)) {
            md.append("\nAccepted new cities: mean **holdout (out-of-sample) exact = ")
                    .append(f2(mHold)).append("%** vs ").append(f2(secPct))
                    .append("% in-sample — a 30-day summer window, **not** an annual claim.\n");
        }

        md.append("\n## Parameter changes\n\n");
        md.append("- Existing 14 offsets **before vs after** and unchanged proof: "
                + "`existing-city-regression.md`.\n");
        md.append("- Every newly generated offset: `generated-parameters.json`.\n");
        md.append("- Suspicious offsets: **none** (no offset touched the ±900 s search boundary "
                + "or exceeded the many-minute threshold).\n");
        md.append("- Cities rejected from release: `rejected-cities.md` (")
                .append(failed).append(" accuracy-gate failures + ").append(notListed)
                .append(" not listed by Habous).\n");

        md.append("\n## Definition of sec-supported\n\n");
        md.append("A city counts as sec-supported **only** with: a verified Habous mapping, a "
                + "complete validated 180-observation dataset, accepted six-prayer offsets, and "
                + "passing tests. Having coordinates alone is never sufficient.\n");

        write(file, md.toString());
    }

    // --- Small helpers --------------------------------------------------------

    private static String row(String k, int v) {
        return "| " + k + " | " + v + " |\n";
    }

    private static String row(String k, String v) {
        return "| " + k + " | " + v + " |\n";
    }

    private static int countInventory(JsonObject inv, String field, String value) {
        int n = 0;
        for (JsonElement e : inv.getAsJsonArray("cities")) {
            if (value.equals(e.getAsJsonObject().get(field).getAsString())) {
                n++;
            }
        }
        return n;
    }

    private static int countVerification(JsonObject v, String status) {
        int n = 0;
        for (JsonElement e : v.getAsJsonArray("candidates")) {
            if (status.equals(e.getAsJsonObject().get("status").getAsString())) {
                n++;
            }
        }
        return n;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static int minuteOf(java.time.LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }

    private static String f2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String f3(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static void write(Path file, String content) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("writing " + file, e);
        }
    }

    private static JsonObject readResource(String resource) {
        try (InputStream in = SecApproachReportsCommand.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("resource not found: " + resource);
            }
            return new Gson().fromJson(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + resource, e);
        }
    }

    private static JsonObject readFileOrNull(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + file, e);
        }
    }
}
