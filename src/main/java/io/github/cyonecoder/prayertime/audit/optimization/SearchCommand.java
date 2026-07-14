package io.github.cyonecoder.prayertime.audit.optimization;

import io.github.cyonecoder.prayertime.audit.dataset.HabousDatasetCommand;
import io.github.cyonecoder.prayertime.audit.optimization.ParameterSearchService.PrayerSearchResult;
import io.github.cyonecoder.prayertime.audit.optimization.PrayerParameterSearch.CombinedResult;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The {@code research} CLI verb: parameter search, rounding search, and
 * candidate re-ranking. This is a research/audit entry point; it never changes
 * production defaults and only reads the reference dataset.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code research search-parameter --prayer <p> --dataset <dir>
 *       [--cities a,b] [--from d] [--to d] [--output dir]}</li>
 *   <li>{@code research search-rounding --dataset <dir> [--cities a,b]
 *       [--from d] [--to d] [--output dir]}</li>
 *   <li>{@code research rank-candidates --input <dir> [--top N]}</li>
 * </ul>
 */
public final class SearchCommand {

    private static final Path DEFAULT_OUTPUT = Path.of("build/reports/habous-search");
    private static final RoundingPolicy DEFAULT_POLICY = RoundingPolicy.NEAREST_MINUTE;
    private static final OperationOrder DEFAULT_ORDER = OperationOrder.RAW_THEN_ADJUST_THEN_ROUND;

    private final CityLocations locations;
    private final HabousRawEngine engine;

    public SearchCommand() {
        this(new CityLocations(), new HabousRawEngine());
    }

    public SearchCommand(CityLocations locations, HabousRawEngine engine) {
        this.locations = locations;
        this.engine = engine;
    }

    /** Signals a clean user-input error (reported without a stack trace). */
    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    /**
     * Runs a {@code research} invocation.
     *
     * @param args the full argument array (args[0] == "research")
     * @param out  normal output stream
     * @param err  error output stream
     * @return process exit code (0 ok, 2 usage error, 1 unexpected)
     */
    public int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length < 2) {
                throw new UsageException("missing research subcommand");
            }
            String sub = args[1];
            Map<String, String> flags = parseFlags(args, 2);
            switch (sub) {
                case "search-parameter" -> searchParameter(flags, out);
                case "search-rounding" -> searchRounding(flags, out);
                case "rank-candidates" -> rankCandidates(flags, out);
                case "fetch-habous" -> new HabousDatasetCommand().fetch(flags, out);
                case "fetch-habous-live" -> new HabousDatasetCommand().fetchLive(flags, out);
                case "verify-habous-cities" ->
                        new io.github.cyonecoder.prayertime.audit.dataset
                                .HabousCityVerificationCommand().run(flags, out);
                case "train-secaproach" ->
                        new io.github.cyonecoder.prayertime.audit.secapproach
                                .SecApproachTrainingCommand(new MoroccoCityRegistry(), engine)
                                .run(flags, out);
                case "train-new-cities" ->
                        new io.github.cyonecoder.prayertime.audit.secapproach
                                .SecApproachNewCityTrainingCommand(new MoroccoCityRegistry(), engine)
                                .run(flags, out);
                case "dataset-coverage" -> new HabousDatasetCommand().coverage(flags, out);
                case "infer-global-model" ->
                        new io.github.cyonecoder.prayertime.audit.model.GlobalModelCommand(
                                locations, engine).infer(flags, out);
                default -> throw new UsageException("unknown research subcommand: " + sub);
            }
            return 0;
        } catch (UsageException e) {
            err.println("Error: " + e.getMessage());
            printUsage(err);
            return 2;
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            return 2;
        } catch (RuntimeException e) {
            err.println("Unexpected error: " + e.getMessage());
            return 1;
        }
    }

    // --- Subcommands ----------------------------------------------------------

    private void searchParameter(Map<String, String> flags, PrintStream out) {
        Prayer prayer = parsePrayer(require(flags, "prayer"));
        ReferenceDataset dataset = loadDataset(flags);
        Path output = flags.containsKey("output") ? Path.of(flags.get("output")) : DEFAULT_OUTPUT;

        ParameterSearchService service = new ParameterSearchService(engine, locations);
        PrayerParameterSearch search = new PrayerParameterSearch(service);
        PrayerSearchResult result = search.searchPrayer(prayer, dataset, DEFAULT_POLICY, DEFAULT_ORDER);

        Map<String, Object> metadata = baseMetadata(flags, dataset);
        metadata.put("mode", "search-parameter");
        metadata.put("prayer", prayer.name());
        metadata.put("parameter", result.parameter());
        metadata.put("range", rangeMetadata(search.ranges().forPrayer(prayer)));
        metadata.put("roundingPolicy", DEFAULT_POLICY.name());
        metadata.put("operationOrder", DEFAULT_ORDER.name());
        metadata.put("candidateCount", result.ranked().size());

        SearchReportWriter.ReportData data = new SearchReportWriter.ReportData(
                "Habous " + prayer + " parameter search",
                result.ranked(), result.intervals(), metadata, topN(flags, 50));
        Path written = new SearchReportWriter().write(output, data);

        out.println("Searched " + result.parameter() + " for " + prayer
                + " over " + result.ranked().size() + " candidates.");
        printBest(out, result.ranked());
        printBestInterval(out, result.intervals());
        out.println("Report written to " + written);
    }

    private void searchRounding(Map<String, String> flags, PrintStream out) {
        ReferenceDataset dataset = loadDataset(flags);
        Path output = flags.containsKey("output") ? Path.of(flags.get("output")) : DEFAULT_OUTPUT;

        // Fix the astronomy first with the independent per-prayer searches, then
        // vary only the rounding policy and operation order.
        ParameterSearchService service = new ParameterSearchService(engine, locations);
        PrayerParameterSearch search = new PrayerParameterSearch(service);
        CombinedResult combined = search.searchAll(dataset, DEFAULT_POLICY, DEFAULT_ORDER);

        RoundingPolicySearch roundingSearch = new RoundingPolicySearch(engine, locations);
        List<CandidateResult> ranked = roundingSearch.search(
                combined.combinedCandidate(), dataset, PrayerParameterSearch.SEARCH_ORDER);

        Map<String, Object> metadata = baseMetadata(flags, dataset);
        metadata.put("mode", "search-rounding");
        metadata.put("roundingPolicies", java.util.Arrays.stream(RoundingPolicy.values())
                .map(Enum::name).toList());
        metadata.put("operationOrders", java.util.Arrays.stream(OperationOrder.values())
                .map(Enum::name).toList());
        metadata.put("candidateCount", ranked.size());

        SearchReportWriter.ReportData data = new SearchReportWriter.ReportData(
                "Habous rounding-policy search", ranked, List.of(), metadata, topN(flags, 50));
        Path written = new SearchReportWriter().write(output, data);

        out.println("Evaluated " + ranked.size() + " rounding/operation combinations.");
        printBest(out, ranked);
        out.println("Report written to " + written);
    }

    private void rankCandidates(Map<String, String> flags, PrintStream out) {
        Path input = Path.of(require(flags, "input"));
        Path csv = Files.isDirectory(input) ? input.resolve("all-candidates.csv") : input;
        if (!Files.isRegularFile(csv)) {
            throw new IllegalArgumentException("all-candidates.csv not found at " + csv);
        }
        int top = topN(flags, 50);
        List<CsvRow> rows = readCandidateCsv(csv);
        rows.sort(CsvRow.RANKING);

        out.println("Re-ranked " + rows.size() + " candidates from " + csv);
        out.printf(Locale.ROOT, "%-4s %-11s %-11s %-16s %-16s %8s %7s %6s %6s%n",
                "#", "sunriseAlt", "maghribAlt", "rounding", "order",
                "exact%", "maxErr", "MAE", "params");
        int limit = Math.min(top, rows.size());
        for (int i = 0; i < limit; i++) {
            CsvRow r = rows.get(i);
            out.printf(Locale.ROOT, "%-4d %-11.4f %-11.4f %-16s %-16s %8.1f %7d %6.3f %6d%n",
                    i + 1, r.sunriseAltitude, r.maghribAltitude, r.roundingPolicy, r.operationOrder,
                    r.exactPercentage, r.maxAbsError, r.meanAbsError, r.parameterCount);
        }
    }

    // --- Loading & parsing ----------------------------------------------------

    private ReferenceDataset loadDataset(Map<String, String> flags) {
        Path dir = Path.of(require(flags, "dataset"));
        ReferenceDataset full = ReferenceDataset.load(dir);
        Set<String> cities = parseCities(flags.get("cities"));
        LocalDate from = parseDate(flags.get("from"), "from");
        LocalDate to = parseDate(flags.get("to"), "to");
        if (cities.isEmpty() && from == null && to == null) {
            return full;
        }
        return full.filter(cities, from, to);
    }

    private static Set<String> parseCities(String value) {
        Set<String> out = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return out;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static LocalDate parseDate(String value, String flag) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("invalid date for --" + flag + ": " + value);
        }
    }

    static Prayer parsePrayer(String raw) {
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "sunrise", "chourouk", "shourouk", "shuruq", "shuruk" -> Prayer.SUNRISE;
            case "fajr", "fadjr" -> Prayer.FAJR;
            case "dhuhr", "duhr", "dohr", "zuhr" -> Prayer.DHUHR;
            case "asr" -> Prayer.ASR;
            case "maghrib", "maghreb" -> Prayer.MAGHRIB;
            case "isha", "ishaa" -> Prayer.ISHA;
            default -> throw new IllegalArgumentException("unknown prayer: " + raw);
        };
    }

    private int topN(Map<String, String> flags, int fallback) {
        String value = flags.get("top");
        if (value == null) {
            return fallback;
        }
        try {
            int n = Integer.parseInt(value);
            if (n <= 0) {
                throw new IllegalArgumentException("--top must be positive but was " + n);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid --top: " + value);
        }
    }

    private Map<String, Object> baseMetadata(Map<String, String> flags, ReferenceDataset dataset) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dataset", flags.get("dataset"));
        m.put("cities", dataset.cities());
        m.put("dates", dataset.dates().stream().map(LocalDate::toString).toList());
        m.put("observationCount", dataset.observations().size());
        m.put("generatedAt", java.time.OffsetDateTime.now(ZoneOffset.UTC).toString());
        return m;
    }

    private static Map<String, Object> rangeMetadata(ParameterRange range) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", range.name());
        m.put("min", range.min().doubleValue());
        m.put("max", range.max().doubleValue());
        m.put("step", range.step().doubleValue());
        m.put("gridPoints", range.size());
        return m;
    }

    // --- CSV re-ranking -------------------------------------------------------

    /** A parsed row of {@code all-candidates.csv}, enough to re-rank. */
    private record CsvRow(
            double sunriseAltitude, double maghribAltitude,
            String roundingPolicy, String operationOrder,
            double exactPercentage, int maxAbsError, double meanAbsError,
            double errorStandardDeviation, int parameterCount, double crossCityStability,
            String signature) {

        static final Comparator<CsvRow> RANKING =
                Comparator.comparingDouble(CsvRow::exactPercentage).reversed()
                        .thenComparingInt(CsvRow::maxAbsError)
                        .thenComparingDouble(CsvRow::meanAbsError)
                        .thenComparingDouble(CsvRow::errorStandardDeviation)
                        .thenComparingInt(CsvRow::parameterCount)
                        .thenComparing(Comparator.comparingDouble(CsvRow::crossCityStability).reversed())
                        .thenComparing(CsvRow::signature);
    }

    private List<CsvRow> readCandidateCsv(Path csv) {
        List<CsvRow> rows = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] f = line.split(",");
                // Columns per SearchReportWriter#writeAllCandidatesCsv.
                double sunrise = Double.parseDouble(f[4]);
                double maghrib = Double.parseDouble(f[5]);
                String rounding = f[6];
                String order = f[7];
                double exactPct = Double.parseDouble(f[10]);
                double meanAbs = Double.parseDouble(f[12]);
                int maxAbs = Integer.parseInt(f[13]);
                double stdDev = Double.parseDouble(f[14]);
                int params = Integer.parseInt(f[18]);
                double stability = Double.parseDouble(f[19]);
                String signature = f[1] + ";" + f[2] + ";" + f[3] + ";" + f[4] + ";" + f[5]
                        + ";" + rounding + ";" + order;
                rows.add(new CsvRow(sunrise, maghrib, rounding, order, exactPct, maxAbs,
                        meanAbs, stdDev, params, stability, signature));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + csv, e);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Malformed all-candidates.csv: " + e.getMessage(), e);
        }
        return rows;
    }

    // --- Output helpers -------------------------------------------------------

    private void printBest(PrintStream out, List<CandidateResult> ranked) {
        if (ranked.isEmpty()) {
            out.println("No candidates evaluated.");
            return;
        }
        CandidateResult best = ranked.get(0);
        CandidateScore s = best.score();
        out.printf(Locale.ROOT,
                "Best: exact=%d/%d (%.1f%%) MAE=%.3f maxErr=%d stdDev=%.3f params=%d%n",
                s.exactMinuteMatches(), s.total(), s.exactPercentage(),
                s.meanAbsoluteErrorMinutes(), s.maximumAbsoluteErrorMinutes(),
                s.errorStandardDeviation(), s.parameterCount());
    }

    private void printBestInterval(PrintStream out, List<ParameterSearchService.ExactMatchInterval> intervals) {
        intervals.stream()
                .max(Comparator.comparingDouble(ParameterSearchService.ExactMatchInterval::exactPercentage)
                        .thenComparingDouble(ParameterSearchService.ExactMatchInterval::width))
                .ifPresent(best -> out.printf(Locale.ROOT,
                        "Best equivalent interval: %s in [%.4f, %.4f] (width %.4f) → %.1f%% exact%n",
                        best.parameter(), best.fromValue(), best.toValue(), best.width(),
                        best.exactPercentage()));
    }

    private static Map<String, String> parseFlags(String[] args, int startIndex) {
        Map<String, String> flags = new LinkedHashMap<>();
        for (int i = startIndex; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new UsageException("unexpected argument: " + arg);
            }
            String key = arg.substring(2);
            if (i + 1 >= args.length) {
                throw new UsageException("missing value for --" + key);
            }
            flags.put(key, args[++i]);
        }
        return flags;
    }

    private static String require(Map<String, String> flags, String key) {
        String value = flags.get(key);
        if (value == null) {
            throw new UsageException("missing required flag --" + key);
        }
        return value;
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage:");
        out.println("  research search-parameter --prayer <p> --dataset <dir> "
                + "[--cities a,b] [--from d] [--to d] [--output dir]");
        out.println("  research search-rounding --dataset <dir> "
                + "[--cities a,b] [--from d] [--to d] [--output dir]");
        out.println("  research rank-candidates --input <dir> [--top N]");
        out.println("  research fetch-habous --cities <all-mapped|a,b> --year <y> --months <1-12|1,2> "
                + "[--output dir] [--delay-seconds n] [--retries n] [--refresh] [--report dir]");
        out.println("  research dataset-coverage --dataset <dir> --year <y> --months <1-12|1,2> "
                + "[--output dir]");
        out.println("  research infer-global-model --dataset <dir> [--output dir]");
        out.println("  research fetch-habous-live --cities <all-mapped|a,b> "
                + "[--output dir] [--delay-seconds n]");
        out.println("  research train-secaproach --dataset <dir> "
                + "[--base file] [--min-exact 0.95] [--low -900] [--high 900] "
                + "[--offsets-out file] [--report-out file] [--holdout-out file]");
        out.println("  research train-new-cities --dataset <dir> "
                + "[--min-exact 0.95] [--low -900] [--high 900] "
                + "[--suspicious-seconds 600] [--output file]");
        out.println("  research verify-habous-cities "
                + "[--delay-seconds n] [--dropdown-ville id] [--mapping file] "
                + "[--options-out file] [--report-out file]");
    }
}
