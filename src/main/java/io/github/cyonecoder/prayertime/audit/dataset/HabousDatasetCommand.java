package io.github.cyonecoder.prayertime.audit.dataset;

import io.github.cyonecoder.prayertime.audit.dataset.HabousReferenceCollector.Outcome;
import io.github.cyonecoder.prayertime.audit.dataset.HabousReferenceCollector.Record;
import io.github.cyonecoder.prayertime.audit.dataset.HabousReferenceCollector.Sleeper;
import io.github.cyonecoder.prayertime.audit.dataset.HabousReferenceCollector.Status;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient.Fetcher;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousHtmlParser;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * CLI handlers for the dataset subcommands {@code research fetch-habous} and
 * {@code research dataset-coverage}. Usage problems are reported as {@link
 * IllegalArgumentException} so the parent command maps them to a usage exit code.
 */
public final class HabousDatasetCommand {

    private static final Path DEFAULT_DATASET = Path.of("research-data/habous");
    private static final Path DEFAULT_REPORT = Path.of("build/reports/habous-dataset");

    private final Fetcher fetcher;
    private final Sleeper sleeper;
    private final Clock clock;

    public HabousDatasetCommand() {
        this(new HabousClient()::fetchMonthlyHtml, duration -> Thread.sleep(duration.toMillis()),
                Clock.systemUTC());
    }

    public HabousDatasetCommand(Fetcher fetcher, Sleeper sleeper, Clock clock) {
        this.fetcher = fetcher;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    /** Handles {@code research fetch-habous}. */
    public void fetch(Map<String, String> flags, PrintStream out) {
        HabousCityMapping mapping = HabousCityMapping.load();
        HabousDatasetSplit split = HabousDatasetSplit.load();
        int year = requireInt(flags, "year");
        List<Integer> months = parseMonths(require(flags, "months"));
        List<String> cities = resolveCities(require(flags, "cities"), mapping);
        Path datasetRoot = flags.containsKey("output") ? Path.of(flags.get("output")) : DEFAULT_DATASET;
        Path reportDir = flags.containsKey("report") ? Path.of(flags.get("report")) : DEFAULT_REPORT;

        CollectionSettings settings = CollectionSettings.defaults()
                .withRequestDelay(Duration.ofSeconds(longFlag(flags, "delay-seconds", 2)))
                .withMaxRetries((int) longFlag(flags, "retries", 3))
                .withRefresh(Boolean.parseBoolean(flags.getOrDefault("refresh", "false")));

        HabousSnapshotStore store = new HabousSnapshotStore(datasetRoot);
        HabousReferenceCollector collector = new HabousReferenceCollector(
                fetcher, new HabousHtmlParser(), store, mapping, clock, sleeper, settings);

        Outcome outcome = collector.collect(cities, year, months);
        printOutcome(out, outcome);

        Path report = new HabousDatasetCoverageReport(mapping, split, store)
                .generate(reportDir, year, months, Optional.of(outcome));
        out.println("Coverage report written to " + report);
    }

    /**
     * Handles {@code research fetch-habous-live}: fetches the current live Hijri
     * month for the given cities using the trust-augmented client and the
     * nine-column parser, writing dataset JSON with source provenance.
     */
    public void fetchLive(Map<String, String> flags, PrintStream out) {
        HabousCityMapping mapping = HabousCityMapping.load();
        List<String> cities = resolveCities(require(flags, "cities"), mapping);
        Path datasetRoot = flags.containsKey("output") ? Path.of(flags.get("output")) : DEFAULT_DATASET;
        Duration delay = Duration.ofSeconds(longFlag(flags, "delay-seconds", 2));

        List<LiveHabousCollector.Result> results =
                LiveHabousCollector.live(delay).collect(cities, datasetRoot);

        Map<LiveHabousCollector.Status, Integer> counts =
                new EnumMap<>(LiveHabousCollector.Status.class);
        int totalDays = 0;
        for (LiveHabousCollector.Result r : results) {
            counts.merge(r.status(), 1, Integer::sum);
            totalDays += r.dayCount();
            out.printf(Locale.ROOT, "  %-12s %-14s %s%n", r.city(), r.status(),
                    r.isSaved() ? (r.firstDate() + ".." + r.lastDate() + " (" + r.dayCount() + "d)")
                            : r.message());
        }
        out.println("Live collection: " + results.size() + " cities, " + totalDays
                + " day-records written to " + datasetRoot);
        for (Map.Entry<LiveHabousCollector.Status, Integer> e : counts.entrySet()) {
            out.printf(Locale.ROOT, "  %-16s %d%n", e.getKey(), e.getValue());
        }
    }

    /** Handles {@code research dataset-coverage}. */
    public void coverage(Map<String, String> flags, PrintStream out) {
        HabousCityMapping mapping = HabousCityMapping.load();
        HabousDatasetSplit split = HabousDatasetSplit.load();
        int year = requireInt(flags, "year");
        List<Integer> months = parseMonths(require(flags, "months"));
        Path datasetRoot = Path.of(require(flags, "dataset"));
        Path reportDir = flags.containsKey("output") ? Path.of(flags.get("output")) : DEFAULT_REPORT;

        HabousSnapshotStore store = new HabousSnapshotStore(datasetRoot);
        Path report = new HabousDatasetCoverageReport(mapping, split, store)
                .generate(reportDir, year, months, Optional.empty());
        out.println("Coverage report written to " + report);
    }

    // --- Helpers --------------------------------------------------------------

    private void printOutcome(PrintStream out, Outcome outcome) {
        Map<Status, Integer> counts = new EnumMap<>(Status.class);
        for (Record r : outcome.records()) {
            counts.merge(r.status(), 1, Integer::sum);
        }
        out.println("Collected " + outcome.records().size() + " city-months for " + outcome.year() + ":");
        for (Map.Entry<Status, Integer> e : counts.entrySet()) {
            out.printf(Locale.ROOT, "  %-16s %d%n", e.getKey(), e.getValue());
        }
    }

    private static List<String> resolveCities(String value, HabousCityMapping mapping) {
        if (value.trim().equalsIgnoreCase("all-mapped")) {
            return mapping.mappedCityIds();
        }
        List<String> cities = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                cities.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        if (cities.isEmpty()) {
            throw new IllegalArgumentException("no cities resolved from --cities " + value);
        }
        return cities;
    }

    /** Parses "1-12" or "1,3,5" or "7" into an ascending, de-duplicated month list. */
    static List<Integer> parseMonths(String value) {
        java.util.TreeSet<Integer> months = new java.util.TreeSet<>();
        for (String part : value.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (token.contains("-")) {
                String[] bounds = token.split("-", 2);
                int from = parseMonth(bounds[0]);
                int to = parseMonth(bounds[1]);
                if (to < from) {
                    throw new IllegalArgumentException("invalid month range: " + token);
                }
                for (int m = from; m <= to; m++) {
                    months.add(m);
                }
            } else {
                months.add(parseMonth(token));
            }
        }
        if (months.isEmpty()) {
            throw new IllegalArgumentException("no months parsed from '" + value + "'");
        }
        return new ArrayList<>(months);
    }

    private static int parseMonth(String token) {
        try {
            int month = Integer.parseInt(token.trim());
            if (month < 1 || month > 12) {
                throw new IllegalArgumentException("month out of range 1..12: " + token);
            }
            return month;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid month: '" + token + "'");
        }
    }

    private static String require(Map<String, String> flags, String key) {
        String value = flags.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing required flag --" + key);
        }
        return value;
    }

    private static int requireInt(Map<String, String> flags, String key) {
        try {
            return Integer.parseInt(require(flags, key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid integer for --" + key);
        }
    }

    private static long longFlag(Map<String, String> flags, String key, long fallback) {
        String value = flags.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new IllegalArgumentException("--" + key + " must be >= 0");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid number for --" + key + ": " + value);
        }
    }
}
