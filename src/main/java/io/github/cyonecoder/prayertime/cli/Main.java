package io.github.cyonecoder.prayertime.cli;

import io.github.cyonecoder.prayertime.application.service.PrayerComparison;
import io.github.cyonecoder.prayertime.application.service.PrayerTimeComparisonService;
import io.github.cyonecoder.prayertime.application.service.PrayerTimeComparisonService.ComparisonResult;
import io.github.cyonecoder.prayertime.domain.calculation.PrayerTimeCalculator;
import io.github.cyonecoder.prayertime.domain.method.CalculationMethod;
import io.github.cyonecoder.prayertime.domain.method.MoroccoCalculationMethod;
import io.github.cyonecoder.prayertime.domain.model.City;
import io.github.cyonecoder.prayertime.domain.model.PrayerRequest;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimesResult;
import io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousCache;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousHtmlParser;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousPrayerTimesProvider;
import io.github.cyonecoder.prayertime.time.MoroccoCivilTimeService;
import io.github.cyonecoder.prayertime.time.MoroccoClockInfo;
import java.io.PrintStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

/**
 * Command-line interface. This class only parses arguments and delegates to the
 * application/domain services for all computation; it holds no business logic.
 *
 * <p>Commands: {@code calculate}, {@code range}, {@code month}, {@code clock},
 * {@code compare}, {@code cities}. Habous is contacted only for {@code compare}.
 */
public final class Main {

    private static final int EXIT_OK = 0;
    private static final int EXIT_ERROR = 1;
    private static final int EXIT_USAGE = 2;

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final String CALCULATED_SOURCE = "astronomical";

    /** User-input error that should be reported cleanly (no stack trace). */
    private static final class CliException extends RuntimeException {
        CliException(String message) {
            super(message);
        }
    }

    private final MoroccoCityRegistry registry;
    private final PrayerTimeCalculator calculator;
    private final MoroccoCivilTimeService civilTime;
    private final PrayerTimeComparisonService comparison;
    private final HabousPrayerTimesProvider habous;

    public Main() {
        this(new MoroccoCityRegistry(),
                new PrayerTimeCalculator(),
                new MoroccoCivilTimeService(),
                new PrayerTimeComparisonService(),
                new HabousPrayerTimesProvider(
                        new HabousClient()::fetchMonthlyHtml, new HabousHtmlParser(), new HabousCache()));
    }

    Main(MoroccoCityRegistry registry,
         PrayerTimeCalculator calculator,
         MoroccoCivilTimeService civilTime,
         PrayerTimeComparisonService comparison,
         HabousPrayerTimesProvider habous) {
        this.registry = registry;
        this.calculator = calculator;
        this.civilTime = civilTime;
        this.comparison = comparison;
        this.habous = habous;
    }

    public static void main(String[] args) {
        System.exit(new Main().run(args, System.out, System.err));
    }

    /**
     * Parses and runs a command.
     *
     * @return process exit code (0 ok, 2 usage/user error, 1 unexpected)
     */
    int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            err.println("Error: no command given");
            printUsage(err);
            return EXIT_USAGE;
        }
        String command = args[0];
        // Research/audit verb: has its own subcommand + flag grammar, so it is
        // routed before the production flag parser. It never touches production
        // calculation defaults.
        if ("research".equals(command)) {
            return new io.github.cyonecoder.prayertime.audit.optimization.SearchCommand()
                    .run(args, out, err);
        }
        if ("audit".equals(command)) {
            return new io.github.cyonecoder.prayertime.audit.release.AuditCommand()
                    .run(args, out, err);
        }
        Map<String, String> flags;
        try {
            flags = parseFlags(args);
            switch (command) {
                case "calculate" -> calculate(flags, out);
                case "range" -> range(flags, out);
                case "month" -> month(flags, out);
                case "clock" -> clock(flags, out);
                case "compare" -> compare(flags, out);
                case "cities" -> cities(out);
                default -> throw new CliException("unknown command: " + command);
            }
            return EXIT_OK;
        } catch (CliException e) {
            err.println("Error: " + e.getMessage());
            return EXIT_USAGE;
        } catch (RuntimeException e) {
            // Unexpected: report the message without a stack trace.
            err.println("Unexpected error: " + e.getMessage());
            return EXIT_ERROR;
        }
    }

    // --- Commands ----------------------------------------------------------

    private void calculate(Map<String, String> flags, PrintStream out) {
        City city = requireCity(flags);
        LocalDate date = requireDate(flags, "date");
        printDay(out, city, date, true);
    }

    private void range(Map<String, String> flags, PrintStream out) {
        City city = requireCity(flags);
        LocalDate from = requireDate(flags, "from");
        LocalDate to = requireDate(flags, "to");
        if (to.isBefore(from)) {
            throw new CliException("--to (" + to + ") is before --from (" + from + ")");
        }
        printHeader(out, city, "range " + from + " .. " + to);
        printTableHeader(out);
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            printRow(out, d, calculate(city, d));
        }
    }

    private void month(Map<String, String> flags, PrintStream out) {
        City city = requireCity(flags);
        int year = requireInt(flags, "year");
        int month = requireInt(flags, "month");
        YearMonth ym;
        try {
            ym = YearMonth.of(year, month);
        } catch (RuntimeException e) {
            throw new CliException("invalid year/month: " + year + "/" + month);
        }
        printHeader(out, city, "month " + ym);
        printTableHeader(out);
        for (int day = 1; day <= ym.lengthOfMonth(); day++) {
            LocalDate d = ym.atDay(day);
            printRow(out, d, calculate(city, d));
        }
    }

    private void clock(Map<String, String> flags, PrintStream out) {
        LocalDate date = requireDate(flags, "date");
        MoroccoClockInfo info = civilTime.clockInfoFor(date);
        out.println("Date:            " + date);
        out.println("Timezone:        " + info.zoneId());
        out.println("UTC offset:      " + formatOffset(info.offset()));
        out.println("Clock mode:      " + info.mode());
        out.println("Next transition: "
                + info.nextTransition().map(Object::toString).orElse("(none)"));
        out.println("Prev transition: "
                + info.previousTransition().map(Object::toString).orElse("(none)"));
    }

    private void compare(Map<String, String> flags, PrintStream out) {
        City city = requireCity(flags);
        LocalDate date = requireDate(flags, "date");

        PrayerTimes calculated = calculate(city, date);
        Optional<PrayerTimesResult> official = habous.find(city, date); // only place Habous is called
        ComparisonResult result = comparison.compare(calculated, CALCULATED_SOURCE, official);

        printHeader(out, city, "compare " + date);
        if (result instanceof ComparisonResult.OfficialUnavailable unavailable) {
            out.println("Official Habous data: UNAVAILABLE (" + unavailable.reason() + ")");
            out.println("No comparison performed; calculated times were not substituted.");
            return;
        }
        ComparisonResult.Compared report = (ComparisonResult.Compared) result;
        out.println("Calculated source: " + report.calculatedSource());
        out.println("Official source:   " + report.officialSource());
        out.println();
        out.printf("%-10s | %-10s | %-6s | %-6s | %s%n",
                "Prayer", "Calculated", "Habous", "Diff", "Pass");
        for (PrayerComparison c : report.prayerComparisons()) {
            printComparisonRow(out, c);
        }
        out.println("(sunrise reported separately, not part of pass/fail)");
        printComparisonRow(out, report.sunriseComparison());
        out.println();
        out.printf("Mean abs error: %.2f min  Max abs error: %d min  Result: %s%n",
                report.meanAbsoluteErrorMinutes(), report.maxAbsoluteErrorMinutes(),
                report.allWithinTolerance() ? "PASS" : "FAIL");
    }

    private void cities(PrintStream out) {
        out.printf("%-12s %-14s %10s %10s%n", "id", "name", "lat", "lon");
        for (City city : registry.all()) {
            out.printf("%-12s %-14s %10.4f %10.4f%n",
                    city.id(), city.displayName(),
                    city.coordinates().latitude(), city.coordinates().longitude());
        }
    }

    // --- Rendering helpers -------------------------------------------------

    private void printDay(PrintStream out, City city, LocalDate date, boolean withHeader) {
        PrayerTimes times = calculate(city, date);
        if (withHeader) {
            printHeader(out, city, date.toString());
        }
        out.println("Fajr:    " + times.fajr().format(HM));
        out.println("Sunrise: " + times.sunrise().format(HM));
        out.println("Dhuhr:   " + times.dhuhr().format(HM));
        out.println("Asr:     " + times.asr().format(HM));
        out.println("Maghrib: " + times.maghrib().format(HM));
        out.println("Isha:    " + times.isha().format(HM));
    }

    private void printHeader(PrintStream out, City city, String subject) {
        LocalDate refDate = firstDateOf(subject);
        CalculationMethod method = new MoroccoCalculationMethod();
        ZoneOffset offset = city.zone().getRules().getOffset(
                (refDate == null ? LocalDate.now(ZoneOffset.UTC) : refDate).atTime(12, 0));
        out.println("City:        " + city.displayName() + " (" + city.id() + ")");
        out.println("Subject:     " + subject);
        out.println("Timezone:    " + city.zone());
        if (refDate != null) {
            out.println("UTC offset:  " + formatOffset(offset));
            out.println("Clock mode:  " + civilTime.clockInfoFor(refDate).mode());
        }
        out.println("Method:      " + method.displayName());
        out.println("Source:      " + CALCULATED_SOURCE);
        out.println();
    }

    private void printTableHeader(PrintStream out) {
        out.printf("%-11s %5s %7s %5s %5s %7s %5s%n",
                "Date", "Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha");
    }

    private void printRow(PrintStream out, LocalDate date, PrayerTimes t) {
        out.printf("%-11s %5s %7s %5s %5s %7s %5s%n",
                date, t.fajr().format(HM), t.sunrise().format(HM), t.dhuhr().format(HM),
                t.asr().format(HM), t.maghrib().format(HM), t.isha().format(HM));
    }

    private void printComparisonRow(PrintStream out, PrayerComparison c) {
        out.printf("%-10s | %-10s | %-6s | %+5d | %s%n",
                c.prayer(), c.calculated().format(HM), c.official().format(HM),
                c.signedDifferenceMinutes(), c.withinTolerance() ? "yes" : "NO");
    }

    // --- Domain calls ------------------------------------------------------

    private PrayerTimes calculate(City city, LocalDate date) {
        PrayerRequest request =
                PrayerRequest.of(date, city.coordinates(), city.zone(), new MoroccoCalculationMethod());
        return calculator.calculate(request);
    }

    // --- Argument parsing --------------------------------------------------

    private static Map<String, String> parseFlags(String[] args) {
        Map<String, String> flags = new java.util.LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new CliException("unexpected argument: " + arg);
            }
            String key = arg.substring(2);
            if (i + 1 >= args.length) {
                throw new CliException("missing value for --" + key);
            }
            flags.put(key, args[++i]);
        }
        return flags;
    }

    private City requireCity(Map<String, String> flags) {
        String value = require(flags, "city");
        return registry.findByNameOrAlias(value)
                .or(() -> registry.findById(value))
                .orElseThrow(() -> new CliException("unknown city: " + value));
    }

    private LocalDate requireDate(Map<String, String> flags, String key) {
        String value = require(flags, key);
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new CliException("invalid date for --" + key + ": '" + value + "' (expected YYYY-MM-DD)");
        }
    }

    private int requireInt(Map<String, String> flags, String key) {
        String value = require(flags, key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new CliException("invalid integer for --" + key + ": '" + value + "'");
        }
    }

    private static String require(Map<String, String> flags, String key) {
        String value = flags.get(key);
        if (value == null) {
            throw new CliException("missing required flag --" + key);
        }
        return value;
    }

    /** Extracts the first ISO date embedded in a subject string, if any. */
    private static LocalDate firstDateOf(String subject) {
        for (String token : subject.split("\\s+")) {
            try {
                return LocalDate.parse(token);
            } catch (DateTimeParseException ignored) {
                // keep scanning
            }
        }
        return null;
    }

    private static String formatOffset(ZoneOffset offset) {
        return offset.getTotalSeconds() == 0 ? "+00:00" : offset.getId();
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage:");
        out.println("  calculate --city <c> --date <YYYY-MM-DD>");
        out.println("  range --city <c> --from <date> --to <date>");
        out.println("  month --city <c> --year <y> --month <m>");
        out.println("  clock --date <date>");
        out.println("  compare --city <c> --date <date>");
        out.println("  cities");
    }
}
