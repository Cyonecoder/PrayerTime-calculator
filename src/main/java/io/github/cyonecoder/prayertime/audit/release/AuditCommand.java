package io.github.cyonecoder.prayertime.audit.release;

import io.github.cyonecoder.prayertime.audit.dataset.HabousCityMapping;
import io.github.cyonecoder.prayertime.audit.optimization.CalculationCandidate;
import io.github.cyonecoder.prayertime.audit.optimization.CityLocations;
import io.github.cyonecoder.prayertime.audit.optimization.HabousRawEngine;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset;
import io.github.cyonecoder.prayertime.audit.release.HabousMethodValidator.Group;
import io.github.cyonecoder.prayertime.audit.release.HabousMethodValidator.GroupResult;
import io.github.cyonecoder.prayertime.audit.release.HabousMethodValidator.ValidationResult;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The {@code audit} CLI verb. Currently hosts {@code validate-habous-method},
 * which validates the reconstructed global method against all mapped cities and
 * writes the release report. It is a research/audit entry point: it never
 * changes production defaults and only reads the reference dataset.
 */
public final class AuditCommand {

    private static final Path DEFAULT_OUTPUT = Path.of("build/reports/habous-release");

    /** Signals a clean user-input error (reported without a stack trace). */
    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    /**
     * Runs an {@code audit} invocation.
     *
     * @param args the full argument array (args[0] == "audit")
     * @param out  normal output stream
     * @param err  error output stream
     * @return process exit code (0 ok, 2 usage error, 1 unexpected)
     */
    public int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length < 2) {
                throw new UsageException("missing audit subcommand");
            }
            String sub = args[1];
            Map<String, String> flags = new LinkedHashMap<>();
            Set<String> booleans = new LinkedHashSet<>();
            parse(args, 2, flags, booleans);
            if ("validate-habous-method".equals(sub)) {
                validate(flags, booleans, out);
            } else if ("reconstruct-times".equals(sub)) {
                reconstructTimes(flags, out);
            } else {
                throw new UsageException("unknown audit subcommand: " + sub);
            }
            return 0;
        } catch (UsageException e) {
            err.println("Error: " + e.getMessage());
            printUsage(err);
            return 2;
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.println("Error: " + e.getMessage());
            return 2;
        } catch (RuntimeException e) {
            err.println("Unexpected error: " + e.getMessage());
            return 1;
        }
    }

    private void validate(Map<String, String> flags, Set<String> booleans, PrintStream out) {
        String methodId = require(flags, "method");
        if (!ReconstructedMethod.ID.equals(methodId)) {
            throw new IllegalArgumentException("unknown --method '" + methodId
                    + "'; only " + ReconstructedMethod.ID + " is supported");
        }
        CalculationCandidate method = ReconstructedMethod.frozen();

        ReferenceDataset dataset = loadDataset(flags);
        boolean includeCorrections = booleans.contains("include-approved-corrections");
        boolean includeOverrides = booleans.contains("include-official-overrides");
        CorrectionsLayer fullLayer = CorrectionsLayer.load();
        CorrectionsLayer layer = new CorrectionsLayer(
                includeCorrections ? fullLayer.corrections() : List.of(),
                includeOverrides ? fullLayer.overrides() : List.of());

        Path output = flags.containsKey("output") ? Path.of(flags.get("output")) : DEFAULT_OUTPUT;

        HabousCityMapping mapping = HabousCityMapping.load();
        HabousMethodValidator validator =
                new HabousMethodValidator(new HabousRawEngine(), new CityLocations(), mapping);
        ValidationResult result = validator.validate(methodId, method, dataset, layer);
        ReleaseDecision decision = ReleaseDecision.evaluate(result);

        ReleaseReportWriter.Provenance provenance = new ReleaseReportWriter.Provenance(
                Instant.now().toString(), resolveCommit(),
                "empty by design (pure global formula, no per-city corrections)",
                "empty by design (no reviewed official overrides)");
        Path written = new ReleaseReportWriter().write(output, result, decision, provenance);

        printSummary(out, result, decision, written);
    }

    private static final List<io.github.cyonecoder.prayertime.domain.model.Prayer> PRINT_ORDER =
            List.of(io.github.cyonecoder.prayertime.domain.model.Prayer.FAJR,
                    io.github.cyonecoder.prayertime.domain.model.Prayer.SUNRISE,
                    io.github.cyonecoder.prayertime.domain.model.Prayer.DHUHR,
                    io.github.cyonecoder.prayertime.domain.model.Prayer.ASR,
                    io.github.cyonecoder.prayertime.domain.model.Prayer.MAGHRIB,
                    io.github.cyonecoder.prayertime.domain.model.Prayer.ISHA);

    /** Prints the reconstructed schedule (HH:MM) for a city, optionally vs official. */
    private void reconstructTimes(Map<String, String> flags, PrintStream out) {
        String city = require(flags, "city").trim().toLowerCase(Locale.ROOT);
        CalculationCandidate method = ReconstructedMethod.frozen();
        CityLocations locations = new CityLocations();
        HabousRawEngine engine = new HabousRawEngine();
        CityLocations.Location loc = locations.resolve(city);

        List<java.time.LocalDate> dates = new java.util.ArrayList<>();
        java.util.Map<java.time.LocalDate, ReferenceDataset.Observation> official =
                new java.util.HashMap<>();
        if (flags.containsKey("date")) {
            dates.add(java.time.LocalDate.parse(flags.get("date")));
        } else if (flags.containsKey("dataset")) {
            for (ReferenceDataset.Observation o : loadDataset(flags).observations()) {
                if (o.city().equalsIgnoreCase(city)) {
                    dates.add(o.date());
                    official.put(o.date(), o);
                }
            }
            java.util.Collections.sort(dates);
        } else {
            throw new UsageException("provide --date <yyyy-mm-dd> or --dataset <dir>");
        }
        if (dates.isEmpty()) {
            throw new IllegalArgumentException("no dates to show for city " + city);
        }

        boolean withOfficial = !official.isEmpty();
        out.println("Reconstructed schedule (" + ReconstructedMethod.ID + ") for " + city
                + (withOfficial ? "   [R = reconstructed, O = official, Δ = R−O minutes]" : ""));
        out.printf(Locale.ROOT, "%-12s %6s %6s %6s %6s %6s %6s%n",
                "date", "Fajr", "Shuruq", "Dhuhr", "Asr", "Maghrib", "Isha");
        for (java.time.LocalDate date : dates) {
            StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "%-12s", date));
            for (var prayer : PRINT_ORDER) {
                int min = engine.publishedMinuteOfDay(prayer, method, loc.coordinates(), loc.zone(), date);
                row.append(String.format(Locale.ROOT, " %6s", hhmm(min)));
            }
            out.println(row);
            if (withOfficial && official.containsKey(date)) {
                ReferenceDataset.Observation o = official.get(date);
                StringBuilder off = new StringBuilder(String.format(Locale.ROOT, "%-12s", "  official"));
                StringBuilder diff = new StringBuilder(String.format(Locale.ROOT, "%-12s", "  Δ min"));
                for (var prayer : PRINT_ORDER) {
                    int r = engine.publishedMinuteOfDay(prayer, method, loc.coordinates(), loc.zone(), date);
                    int oMin = o.minuteOfDayFor(prayer);
                    off.append(String.format(Locale.ROOT, " %6s", hhmm(oMin)));
                    diff.append(String.format(Locale.ROOT, " %+6d", r - oMin));
                }
                out.println(off);
                out.println(diff);
            }
        }
    }

    private static String hhmm(int minuteOfDay) {
        return java.time.LocalTime.ofSecondOfDay((long) Math.floorMod(minuteOfDay, 1440) * 60)
                .toString();
    }

    private void printSummary(
            PrintStream out, ValidationResult result, ReleaseDecision decision, Path written) {
        out.println("Habous method validation: " + result.methodId());
        out.println("Cities included: " + result.includedCities().size()
                + " " + result.includedCities());
        if (!result.missingMappedCities().isEmpty()) {
            out.println("Missing mapped cities: " + result.missingMappedCities());
        }
        for (Group group : Group.values()) {
            GroupResult g = result.group(group);
            out.printf(Locale.ROOT,
                    "  %-42s exact=%.2f%% within1=%.2f%% maxErr=%d MAE=%.3f (corr=%d, ovr=%d)%n",
                    group.name(), g.exactPercentage(), g.withinOnePercentage(),
                    g.maxAbsolute(), g.meanAbsolute(), g.correctionsApplied(), g.overridesApplied());
        }
        out.println();
        out.println("Reconstructed-method gate: " + (decision.reconstructedMethodPass() ? "PASS" : "FAIL"));
        decision.reconstructedFailures().forEach(f -> out.println("  - " + f));
        out.println("Habous-compatible gate:    " + (decision.habousCompatiblePass() ? "PASS" : "FAIL"));
        decision.habousCompatibleFailures().forEach(f -> out.println("  - " + f));
        if (!decision.pureFormulaReaches100()) {
            out.printf(Locale.ROOT,
                    "  NOTE: the pure global formula cannot reach 100%% exact-minute (%.2f%%).%n",
                    decision.reconstructedExactPercentage());
        }
        out.println();
        out.println("RELEASE DECISION: " + (decision.releaseApproved() ? "GO" : "NO-GO"));
        out.println("Report written to " + written);
    }

    private ReferenceDataset loadDataset(Map<String, String> flags) {
        Path dir = Path.of(require(flags, "dataset"));
        ReferenceDataset full = ReferenceDataset.load(dir);
        String cities = flags.get("cities");
        if (cities == null || cities.isBlank() || cities.trim().equalsIgnoreCase("all")) {
            return full;
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String part : cities.split(",")) {
            if (!part.trim().isEmpty()) {
                wanted.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        return full.filter(wanted, null, null);
    }

    private static void parse(String[] args, int start, Map<String, String> flags, Set<String> booleans) {
        for (int i = start; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new UsageException("unexpected argument: " + arg);
            }
            String key = arg.substring(2);
            // A boolean flag is one not followed by a value (next token starts with --, or is last).
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                booleans.add(key);
            } else {
                flags.put(key, args[++i]);
            }
        }
    }

    private static String require(Map<String, String> flags, String key) {
        String value = flags.get(key);
        if (value == null) {
            throw new UsageException("missing required flag --" + key);
        }
        return value;
    }

    private static String resolveCommit() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true).start();
            String commit = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.waitFor() == 0 && !commit.isEmpty() ? commit : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage:");
        out.println("  audit validate-habous-method --method " + ReconstructedMethod.ID
                + " --dataset <dir> [--cities all|a,b] "
                + "[--include-approved-corrections] [--include-official-overrides] [--output dir]");
        out.println("  audit reconstruct-times --city <slug> "
                + "(--date <yyyy-mm-dd> | --dataset <dir>)");
    }
}
