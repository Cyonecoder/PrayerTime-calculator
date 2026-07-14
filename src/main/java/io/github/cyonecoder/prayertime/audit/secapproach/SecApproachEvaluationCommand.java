package io.github.cyonecoder.prayertime.audit.secapproach;

import com.google.gson.GsonBuilder;
import io.github.cyonecoder.prayertime.audit.optimization.CalculationCandidate;
import io.github.cyonecoder.prayertime.audit.optimization.HabousRawEngine;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset.Observation;
import io.github.cyonecoder.prayertime.audit.release.ReconstructedMethod;
import io.github.cyonecoder.prayertime.domain.model.City;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Handles {@code research evaluate-secapproach}: scores an existing sec-approach
 * parameter file against a dataset and compares it to a baseline (the global
 * reconstructed formula), without retraining anything.
 *
 * <pre>research evaluate-secapproach --dataset &lt;dir&gt; --parameters &lt;file&gt;
 *   [--baseline global] [--cities selector] [--from d] [--to d] [--output dir]</pre>
 *
 * <p>Research/audit only; it reads bundled resources and the off-classpath dataset
 * and never touches the network.
 */
public final class SecApproachEvaluationCommand {

    private static final Path DEFAULT_OUTPUT =
            Path.of("build/reports/secapproach-all-cities/evaluation-report.json");

    private final MoroccoCityRegistry registry;
    private final HabousRawEngine engine;

    public SecApproachEvaluationCommand() {
        this(new MoroccoCityRegistry(), new HabousRawEngine());
    }

    public SecApproachEvaluationCommand(MoroccoCityRegistry registry, HabousRawEngine engine) {
        this.registry = registry;
        this.engine = engine;
    }

    /** Runs the evaluation. */
    public void run(Map<String, String> flags, PrintStream out) {
        Path datasetDir = Path.of(require(flags, "dataset"));
        Path parameters = Path.of(require(flags, "parameters"));
        String baseline = flags.getOrDefault("baseline", "global").trim().toLowerCase(Locale.ROOT);
        if (!baseline.equals("global") && !baseline.equals("none")) {
            throw new IllegalArgumentException("--baseline must be 'global' or 'none': " + baseline);
        }
        Path outputDir = flags.containsKey("output") ? Path.of(flags.get("output")) : null;
        Path output = flags.containsKey("output-file") ? Path.of(flags.get("output-file"))
                : outputDir != null ? outputDir.resolve("evaluation-report.json") : DEFAULT_OUTPUT;

        Map<String, Map<Prayer, Integer>> offsets =
                SecApproachTrainingCommand.loadOffsets(parameters);
        ReferenceDataset dataset = loadDataset(datasetDir, flags, offsets.keySet());
        CalculationCandidate method = ReconstructedMethod.frozen();

        Map<String, List<Observation>> byCity = new TreeMap<>();
        for (Observation o : dataset.observations()) {
            if (offsets.containsKey(o.city())) {
                byCity.computeIfAbsent(o.city(), k -> new ArrayList<>()).add(o);
            }
        }

        int aggExact = 0;
        int aggWithin1 = 0;
        int aggMax = 0;
        int aggBaseExact = 0;
        int aggN = 0;
        List<Map<String, Object>> cityReports = new ArrayList<>();

        for (Map.Entry<String, List<Observation>> e : byCity.entrySet()) {
            String cityId = e.getKey();
            Optional<City> resolved = registry.findById(cityId).or(() -> registry.findByNameOrAlias(cityId));
            if (resolved.isEmpty()) {
                continue;
            }
            City city = resolved.get();
            Map<Prayer, Integer> off = offsets.get(cityId);
            int exact = 0;
            int within1 = 0;
            int max = 0;
            int baseExact = 0;
            int n = 0;
            for (Observation o : e.getValue()) {
                for (Prayer p : Prayer.values()) {
                    long raw = Math.round(
                            engine.rawSecondsOfDay(p, method, city.coordinates(), city.zone(), o.date()));
                    int predicted = SecApproachEvaluator.predictedMinute(raw, off.getOrDefault(p, 0));
                    int official = minuteOf(o.timeFor(p));
                    int err = Math.abs(SecApproachEvaluator.circularMinuteError(predicted, official));
                    if (err == 0) {
                        exact++;
                    }
                    if (err <= 1) {
                        within1++;
                    }
                    max = Math.max(max, err);
                    if (baseline.equals("global")) {
                        int baseMin = engine.publishedMinuteOfDay(
                                p, method, city.coordinates(), city.zone(), o.date());
                        if (SecApproachEvaluator.circularMinuteError(baseMin, official) == 0) {
                            baseExact++;
                        }
                    }
                    n++;
                }
            }
            aggExact += exact;
            aggWithin1 += within1;
            aggMax = Math.max(aggMax, max);
            aggBaseExact += baseExact;
            aggN += n;
            cityReports.add(cityJson(cityId, exact, within1, max, baseExact, n, baseline));
        }

        double exactPct = pct(aggExact, aggN);
        double within1Pct = pct(aggWithin1, aggN);
        double baseExactPct = pct(aggBaseExact, aggN);
        double improvement = baseline.equals("global") ? exactPct - baseExactPct : 0.0;
        SecApproachGates.Result gate =
                SecApproachGates.aggregate(exactPct, improvement, within1Pct, aggMax);

        write(output, parameters, baseline, datasetDir, cityReports,
                exactPct, within1Pct, aggMax, baseExactPct, improvement, gate);

        out.printf(Locale.ROOT, "Evaluated %s over %d cities (%d observations)%n",
                parameters, byCity.size(), aggN);
        out.printf(Locale.ROOT, "  exact=%.2f%%  within1=%.2f%%  maxAbs=%dmin%n",
                exactPct, within1Pct, aggMax);
        if (baseline.equals("global")) {
            out.printf(Locale.ROOT, "  baseline(global) exact=%.2f%%  improvement=%+.2fpp%n",
                    baseExactPct, improvement);
        }
        out.println("  aggregate gates: " + (gate.passed() ? "PASS" : "FAIL " + gate.failures()));
        out.println("Report → " + output);
    }

    private ReferenceDataset loadDataset(Path dir, Map<String, String> flags, Set<String> paramCities) {
        ReferenceDataset all = ReferenceDataset.load(dir);
        Set<String> cities = flags.containsKey("cities")
                ? new java.util.LinkedHashSet<>(io.github.cyonecoder.prayertime.audit.dataset
                        .ResearchCitySelector.resolve(flags.get("cities")))
                : Set.of();
        LocalDate from = date(flags.get("from"));
        LocalDate to = date(flags.get("to"));
        if (cities.isEmpty() && from == null && to == null) {
            return all;
        }
        return all.filter(cities, from, to);
    }

    private static Map<String, Object> cityJson(
            String city, int exact, int within1, int max, int baseExact, int n, String baseline) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("city", city);
        m.put("observations", n);
        m.put("exactPercentage", round2(pct(exact, n)));
        m.put("withinOnePercentage", round2(pct(within1, n)));
        m.put("maxAbsoluteError", max);
        if (baseline.equals("global")) {
            m.put("baselineExactPercentage", round2(pct(baseExact, n)));
            m.put("improvementPercentagePoints", round2(pct(exact, n) - pct(baseExact, n)));
        }
        return m;
    }

    private void write(
            Path file, Path parameters, String baseline, Path datasetDir,
            List<Map<String, Object>> cityReports, double exactPct, double within1Pct, int maxAbs,
            double baseExactPct, double improvement, SecApproachGates.Result gate) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("note", "Evaluation of a sec-approach parameter file against a dataset, versus the "
                + "global reconstructed formula. Read-only; no retraining, no network.");
        root.put("parameters", parameters.toString());
        root.put("dataset", datasetDir.toString());
        root.put("baseline", baseline);
        Map<String, Object> aggregate = new LinkedHashMap<>();
        aggregate.put("exactPercentage", round2(exactPct));
        aggregate.put("withinOnePercentage", round2(within1Pct));
        aggregate.put("maxAbsoluteError", maxAbs);
        if (baseline.equals("global")) {
            aggregate.put("baselineExactPercentage", round2(baseExactPct));
            aggregate.put("improvementPercentagePoints", round2(improvement));
        }
        aggregate.put("gatesPassed", gate.passed());
        aggregate.put("gateFailures", gate.failures());
        root.put("aggregate", aggregate);
        root.put("cities", cityReports);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root, w);
                w.write("\n");
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("writing " + file, ex);
        }
    }

    private static int minuteOf(java.time.LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }

    private static double pct(int part, int total) {
        return total == 0 ? 0.0 : 100.0 * part / total;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static LocalDate date(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("invalid date: " + value);
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
