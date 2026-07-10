package io.github.cyonecoder.prayertime.cli;

import io.github.cyonecoder.prayertime.application.provider.AstronomicalPrayerTimesProvider;
import io.github.cyonecoder.prayertime.application.provider.HabousCompatiblePrayerTimesProvider;
import io.github.cyonecoder.prayertime.application.provider.HabousDataUnavailableException;
import io.github.cyonecoder.prayertime.application.provider.PrayerSourceMode;
import io.github.cyonecoder.prayertime.application.provider.ProvidedTimes;
import io.github.cyonecoder.prayertime.application.service.PrayerComparison;
import io.github.cyonecoder.prayertime.application.service.PrayerTimeComparisonService;
import io.github.cyonecoder.prayertime.application.service.PrayerTimeComparisonService.ComparisonResult;
import io.github.cyonecoder.prayertime.domain.calculation.PrayerTimeCalculator;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
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
import java.util.List;
import java.util.Locale;
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
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
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
                case "prayer-times" -> prayerTimes(flags, out);
                case "secaproach" -> secaproach(flags, out);
                case "serve" -> serve(flags, out);
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
        printProvidedDay(out, city, date, source(flags), seconds(flags));
    }

    private void range(Map<String, String> flags, PrintStream out) {
        City city = requireCity(flags);
        LocalDate from = requireDate(flags, "from");
        LocalDate to = requireDate(flags, "to");
        if (to.isBefore(from)) {
            throw new CliException("--to (" + to + ") is before --from (" + from + ")");
        }
        List<LocalDate> dates = new java.util.ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            dates.add(d);
        }
        printProvidedTable(out, city, "range " + from + " .. " + to, source(flags), dates, seconds(flags));
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
        List<LocalDate> dates = new java.util.ArrayList<>();
        for (int day = 1; day <= ym.lengthOfMonth(); day++) {
            dates.add(ym.atDay(day));
        }
        printProvidedTable(out, city, "month " + ym, source(flags), dates, seconds(flags));
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

    /** Resolves the requested source mode; defaults to the reconstructed calculation. */
    private static PrayerSourceMode source(Map<String, String> flags) {
        String s = flags.getOrDefault("source", "calculated");
        try {
            return PrayerSourceMode.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CliException(
                    "unknown --source '" + s + "' (use calculated|official|auto|compare)");
        }
    }

    /** Whether the {@code --seconds} diagnostic flag was given. */
    private static boolean seconds(Map<String, String> flags) {
        return flags.containsKey("seconds");
    }

    /** Renders a single day through the provider under the requested source mode. */
    private void printProvidedDay(
            PrintStream out, City city, LocalDate date, PrayerSourceMode mode, boolean seconds) {
        HabousCompatiblePrayerTimesProvider provider = new HabousCompatiblePrayerTimesProvider();
        ProvidedTimes r;
        try {
            r = provider.get(city.id(), date, mode);
        } catch (HabousDataUnavailableException e) {
            out.println("City:        " + city.displayName() + " (" + city.id() + ")");
            out.println("Subject:     " + date);
            out.println("Method:      Habous Official");
            out.println("Source:      official (requested)");
            out.println();
            out.println("Official Habous data: UNAVAILABLE — not substituted with a calculation.");
            throw new CliException(e.getMessage());
        }
        LocalDate refDate = firstDateOf(date.toString());
        out.println("City:        " + city.displayName() + " (" + city.id() + ")");
        out.println("Subject:     " + date);
        out.println("Timezone:    " + city.zone());
        if (refDate != null) {
            out.println("UTC offset:  "
                    + formatOffset(city.zone().getRules().getOffset(refDate.atTime(12, 0))));
            out.println("Clock mode:  " + civilTime.clockInfoFor(refDate).mode());
        }
        out.println("Method:      " + methodLabel(r));
        out.println("Source:      " + sourceLabel(r));
        if (r.parameterVersion() != null) {
            out.println("Parameter:   " + r.parameterVersion());
        }
        out.println();
        if (seconds && !r.official() && r.comparison() == null) {
            // Calculated result: show the raw second-precise time (pre minute-rounding).
            printTimesSeconds(out, city.id(), date);
        } else if (seconds && r.official()) {
            printTimes(out, r.times(), true);
            out.println("(official is published to the minute; seconds shown as :00)");
        } else {
            printTimes(out, r.times(), false);
        }
        if (r.comparison() != null) {
            printProviderComparison(out, city.id(), date, r.comparison(), seconds);
        }
    }

    /** Renders a multi-day table (range/month) through the provider. */
    private void printProvidedTable(
            PrintStream out, City city, String subject, PrayerSourceMode mode, List<LocalDate> dates,
            boolean seconds) {
        HabousCompatiblePrayerTimesProvider provider = new HabousCompatiblePrayerTimesProvider();
        out.println("City:        " + city.displayName() + " (" + city.id() + ")");
        out.println("Subject:     " + subject);
        out.println("Timezone:    " + city.zone());
        out.println("Source:      " + mode.name().toLowerCase(Locale.ROOT));
        out.println();
        if (mode == PrayerSourceMode.COMPARE) {
            printDiffTable(out, provider, city, dates, seconds);
            return;
        }
        DateTimeFormatter fmt = seconds ? HMS : HM;
        int w = seconds ? 8 : 6;
        out.printf("%-11s %-6s %" + w + "s %" + (w + 1) + "s %" + w + "s %" + w + "s %"
                + (w + 1) + "s %" + w + "s%n",
                "Date", "Src", "Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha");
        boolean showSeconds = seconds && mode != PrayerSourceMode.OFFICIAL;
        int unavailable = 0;
        for (LocalDate d : dates) {
            try {
                ProvidedTimes r = provider.get(city.id(), d, mode);
                // For calculated rows, show raw second-precise; official has no seconds.
                String[] cells = showSeconds && !r.official()
                        ? secondCells(city.id(), d)
                        : minuteCells(r.times(), fmt);
                out.printf("%-11s %-6s %" + w + "s %" + (w + 1) + "s %" + w + "s %" + w + "s %"
                        + (w + 1) + "s %" + w + "s%n", d, rowSource(r),
                        cells[0], cells[1], cells[2], cells[3], cells[4], cells[5]);
            } catch (HabousDataUnavailableException e) {
                unavailable++;
                out.printf("%-11s %-6s (official data unavailable)%n", d, "n/a");
            }
        }
        if (mode != PrayerSourceMode.CALCULATED) {
            out.println("  Src: OFF=official, calc=reconstructed, calc*=official-unavailable fallback");
        }
        if (seconds) {
            out.println("  seconds = raw calculated time before minute-rounding (official: :00)");
        }
        if (unavailable > 0) {
            throw new CliException(unavailable + " day(s) had no official data in '"
                    + mode.name().toLowerCase(Locale.ROOT) + "' mode");
        }
    }

    private String[] minuteCells(PrayerTimes t, DateTimeFormatter fmt) {
        return new String[] {t.fajr().format(fmt), t.sunrise().format(fmt), t.dhuhr().format(fmt),
                t.asr().format(fmt), t.maghrib().format(fmt), t.isha().format(fmt)};
    }

    private String[] secondCells(String cityId, LocalDate date) {
        AstronomicalPrayerTimesProvider astro = new AstronomicalPrayerTimesProvider();
        Prayer[] order = {Prayer.FAJR, Prayer.SUNRISE, Prayer.DHUHR,
                Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA};
        String[] cells = new String[6];
        for (int i = 0; i < order.length; i++) {
            cells[i] = astro.secondPreciseTime(cityId, date, order[i]).format(HMS);
        }
        return cells;
    }

    /** Per-day official−calculated difference table for COMPARE mode (minutes, or seconds). */
    private void printDiffTable(
            PrintStream out, HabousCompatiblePrayerTimesProvider provider, City city,
            List<LocalDate> dates, boolean seconds) {
        String unit = seconds ? "seconds" : "minutes";
        AstronomicalPrayerTimesProvider astro = seconds ? new AstronomicalPrayerTimesProvider() : null;
        out.println("Differences: official − calculated, in " + unit
                + (seconds ? " (calculated = raw, before minute-rounding)" : "")
                + " (blank day = no official data)");
        out.printf("%-11s %6s %6s %6s %6s %6s %6s %6s%n",
                "Date", "Fajr", "Shrq", "Dhuhr", "Asr", "Mgrb", "Isha", "|max|");
        int comparedDays = 0;
        int exactDays = 0;
        int worst = 0;
        long absSum = 0;
        long absCount = 0;
        for (LocalDate d : dates) {
            ProvidedTimes.Comparison c = provider.get(city.id(), d, PrayerSourceMode.COMPARE).comparison();
            if (c == null || !c.officialAvailable()) {
                out.printf("%-11s %6s%n", d, "(no official data)");
                continue;
            }
            comparedDays++;
            int[] diffs = new int[6];
            int rowMax = 0;
            Prayer[] order = {Prayer.FAJR, Prayer.SUNRISE, Prayer.DHUHR,
                    Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA};
            for (int i = 0; i < order.length; i++) {
                diffs[i] = seconds
                        ? officialMinusRawSeconds(c, astro, city.id(), d, order[i])
                        : c.differenceMinutes().getOrDefault(order[i], 0);
                rowMax = Math.max(rowMax, Math.abs(diffs[i]));
                absSum += Math.abs(diffs[i]);
                absCount++;
            }
            worst = Math.max(worst, rowMax);
            if (rowMax == 0) {
                exactDays++;
            }
            out.printf("%-11s %6d %6d %6d %6d %6d %6d %6d%n", d,
                    diffs[0], diffs[1], diffs[2], diffs[3], diffs[4], diffs[5], rowMax);
        }
        out.println();
        out.printf(Locale.ROOT,
                "Compared %d day(s): %d exact, mean abs error %.2f %s, max abs error %d %s%n",
                comparedDays, exactDays, absCount == 0 ? 0.0 : (double) absSum / absCount, unit,
                worst, unit);
    }

    /** official (HH:mm, seconds 0) minus the raw calculated time, in seconds. */
    private static int officialMinusRawSeconds(
            ProvidedTimes.Comparison c, AstronomicalPrayerTimesProvider astro,
            String cityId, LocalDate date, Prayer p) {
        int officialSec = timeOf(c.official(), p).toSecondOfDay();
        int rawSec = astro.secondPreciseTime(cityId, date, p).toSecondOfDay();
        return officialSec - rawSec;
    }

    private void printTimes(PrintStream out, PrayerTimes t, boolean seconds) {
        DateTimeFormatter fmt = seconds ? HMS : HM;
        out.println("Fajr:    " + t.fajr().format(fmt));
        out.println("Sunrise: " + t.sunrise().format(fmt));
        out.println("Dhuhr:   " + t.dhuhr().format(fmt));
        out.println("Asr:     " + t.asr().format(fmt));
        out.println("Maghrib: " + t.maghrib().format(fmt));
        out.println("Isha:    " + t.isha().format(fmt));
    }

    /** Prints the raw second-precise calculated schedule (pre minute-rounding). */
    private void printTimesSeconds(PrintStream out, String cityId, LocalDate date) {
        AstronomicalPrayerTimesProvider astro = new AstronomicalPrayerTimesProvider();
        out.println("Fajr:    " + astro.secondPreciseTime(cityId, date, Prayer.FAJR).format(HMS));
        out.println("Sunrise: " + astro.secondPreciseTime(cityId, date, Prayer.SUNRISE).format(HMS));
        out.println("Dhuhr:   " + astro.secondPreciseTime(cityId, date, Prayer.DHUHR).format(HMS));
        out.println("Asr:     " + astro.secondPreciseTime(cityId, date, Prayer.ASR).format(HMS));
        out.println("Maghrib: " + astro.secondPreciseTime(cityId, date, Prayer.MAGHRIB).format(HMS));
        out.println("Isha:    " + astro.secondPreciseTime(cityId, date, Prayer.ISHA).format(HMS));
        out.println("(raw calculated time before minute-rounding)");
    }

    private void printProviderComparison(
            PrintStream out, String cityId, LocalDate date, ProvidedTimes.Comparison c, boolean seconds) {
        out.println();
        if (!c.officialAvailable()) {
            out.println("Comparison: official data unavailable; calculated shown above.");
            return;
        }
        if (seconds) {
            AstronomicalPrayerTimesProvider astro = new AstronomicalPrayerTimesProvider();
            out.printf("%-9s %9s %11s %8s %6s%n",
                    "Prayer", "Official", "Calc(raw)", "Δsec", "Δpub");
            for (Prayer p : Prayer.values()) {
                out.printf("%-9s %9s %11s %+8d %+6d%n", p.name().toLowerCase(Locale.ROOT),
                        timeOf(c.official(), p).format(HM),
                        astro.secondPreciseTime(cityId, date, p).format(HMS),
                        officialMinusRawSeconds(c, astro, cityId, date, p),
                        c.differenceMinutes().getOrDefault(p, 0));
            }
            out.println("Δsec = official − raw calculated (seconds); "
                    + "Δpub = official − published (minutes)");
            return;
        }
        out.printf("%-9s %9s %9s %6s%n", "Prayer", "Official", "Calc", "Diff");
        for (Prayer p : Prayer.values()) {
            out.printf("%-9s %9s %9s %+6d%n", p.name().toLowerCase(Locale.ROOT),
                    timeOf(c.official(), p).format(HM), timeOf(c.calculated(), p).format(HM),
                    c.differenceMinutes().getOrDefault(p, 0));
        }
    }

    private static String methodLabel(ProvidedTimes r) {
        return r.official() ? "Habous Official" : "Morocco Habous Reconstructed";
    }

    private static String sourceLabel(ProvidedTimes r) {
        return switch (r.sourceMode()) {
            case CALCULATED -> "calculated";
            case OFFICIAL -> "official";
            case AUTO -> r.official() ? "auto (official)" : "auto (calculated fallback)";
            case COMPARE -> r.official() ? "compare (official shown)" : "compare (calculated shown)";
        };
    }

    private static String rowSource(ProvidedTimes r) {
        return r.official() ? "OFF" : (r.fallbackUsed() ? "calc*" : "calc");
    }

    private static java.time.LocalTime timeOf(PrayerTimes t, Prayer p) {
        return switch (p) {
            case FAJR -> t.fajr();
            case SUNRISE -> t.sunrise();
            case DHUHR -> t.dhuhr();
            case ASR -> t.asr();
            case MAGHRIB -> t.maghrib();
            case ISHA -> t.isha();
        };
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
            // A boolean flag (e.g. --seconds) is one with no following value: it is
            // either the last token or immediately followed by another --flag.
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                flags.put(key, "true");
            } else {
                flags.put(key, args[++i]);
            }
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

    /**
     * {@code prayer-times --city <c> --date <d> [--source auto|official|calculated|compare]}:
     * serves times through the provider layer and prints the API JSON (machine
     * form). For human-readable day/month tables use {@code calculate}/{@code month}
     * with {@code --source}. Defaults to {@code auto}. OFFICIAL with no official
     * data prints the {@code 503} error body and exits non-zero.
     */
    private void prayerTimes(Map<String, String> flags, PrintStream out) {
        String city = require(flags, "city");
        String source = flags.getOrDefault("source", "auto");
        String date = require(flags, "date");
        var api = new io.github.cyonecoder.prayertime.application.provider.PrayerTimesApiService();
        var response = api.prayerTimes(city, date, source);
        out.println(response.body());
        if (response.status() != 200) {
            throw new CliException("source '" + source + "' returned status " + response.status());
        }
    }

    /**
     * {@code secaproach --city <c> (--date <d> | --month <m> [--year <y>] |
     * --from <d> --to <d>) [--seconds]}: the sec-approach calculation — the
     * reconstructed formula plus a per-city, per-prayer constant seconds offset,
     * floored to the minute (~97% exact vs official). Shows the match against the
     * official timetable when it is available.
     */
    private void secaproach(Map<String, String> flags, PrintStream out) {
        City city = requireCity(flags);
        boolean seconds = seconds(flags);
        var provider = new io.github.cyonecoder.prayertime.application.provider
                .SecondsApproachPrayerTimesProvider();
        if (!provider.supports(city.id())) {
            throw new CliException("no sec-approach offsets for city '" + city.id() + "'");
        }
        var official = new io.github.cyonecoder.prayertime.application.provider
                .HabousOfficialPrayerTimesProvider();
        if (flags.containsKey("date")) {
            secaproachDay(out, provider, official, city, requireDate(flags, "date"), seconds);
        } else {
            List<LocalDate> dates = secaproachDates(flags, city);
            if (flags.containsKey("compare")) {
                secaproachDiffTable(out, provider, official, city, dates, seconds);
            } else {
                secaproachTable(out, provider, official, city, dates, seconds);
            }
        }
    }

    /** Per-day official − sec-approach difference table (minutes, or seconds). */
    private void secaproachDiffTable(
            PrintStream out,
            io.github.cyonecoder.prayertime.application.provider.SecondsApproachPrayerTimesProvider provider,
            io.github.cyonecoder.prayertime.application.provider.HabousOfficialPrayerTimesProvider official,
            City city, List<LocalDate> dates, boolean seconds) {
        String unit = seconds ? "seconds" : "minutes";
        out.println("City:        " + city.displayName() + " (" + city.id() + ")");
        out.println("Method:      Morocco Habous Sec-Approach (city-corrected)");
        out.println("Differences: official − sec-approach, in " + unit
                + (seconds ? " (sec-approach = raw, before floor)" : "")
                + " (blank day = no official data)");
        out.printf("%-11s %6s %6s %6s %6s %6s %6s %6s%n",
                "Date", "Fajr", "Shrq", "Dhuhr", "Asr", "Mgrb", "Isha", "|max|");
        int comparedDays = 0;
        int exactDays = 0;
        int worst = 0;
        long absSum = 0;
        long absCount = 0;
        Prayer[] order = {Prayer.FAJR, Prayer.SUNRISE, Prayer.DHUHR,
                Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA};
        for (LocalDate d : dates) {
            var off = official.officialTimes(city.id(), d);
            if (off.isEmpty()) {
                out.printf("%-11s %6s%n", d, "(no official data)");
                continue;
            }
            comparedDays++;
            PrayerTimes t = provider.computeTimes(city.id(), d);
            int[] diffs = new int[6];
            int rowMax = 0;
            for (int i = 0; i < order.length; i++) {
                Prayer p = order[i];
                diffs[i] = seconds
                        ? timeOf(off.get(), p).toSecondOfDay()
                                - provider.secondPreciseTime(city.id(), d, p).toSecondOfDay()
                        : minuteOf(timeOf(off.get(), p)) - minuteOf(timeOf(t, p));
                rowMax = Math.max(rowMax, Math.abs(diffs[i]));
                absSum += Math.abs(diffs[i]);
                absCount++;
            }
            worst = Math.max(worst, rowMax);
            if (rowMax == 0) {
                exactDays++;
            }
            out.printf("%-11s %6d %6d %6d %6d %6d %6d %6d%n", d,
                    diffs[0], diffs[1], diffs[2], diffs[3], diffs[4], diffs[5], rowMax);
        }
        if (comparedDays > 0) {
            out.println();
            out.printf(Locale.ROOT,
                    "Compared %d day(s): %d exact, mean abs error %.2f %s, max abs error %d %s%n",
                    comparedDays, exactDays,
                    absCount == 0 ? 0.0 : (double) absSum / absCount, unit, worst, unit);
        }
    }

    private List<LocalDate> secaproachDates(Map<String, String> flags, City city) {
        List<LocalDate> dates = new java.util.ArrayList<>();
        if (flags.containsKey("month")) {
            int year = requireInt(flags, "year");
            int month = requireInt(flags, "month");
            YearMonth ym;
            try {
                ym = YearMonth.of(year, month);
            } catch (RuntimeException e) {
                throw new CliException("invalid year/month: " + year + "/" + month);
            }
            for (int d = 1; d <= ym.lengthOfMonth(); d++) {
                dates.add(ym.atDay(d));
            }
        } else if (flags.containsKey("from")) {
            LocalDate from = requireDate(flags, "from");
            LocalDate to = requireDate(flags, "to");
            if (to.isBefore(from)) {
                throw new CliException("--to (" + to + ") is before --from (" + from + ")");
            }
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                dates.add(d);
            }
        } else {
            throw new CliException("provide --date, or --month/--year, or --from/--to");
        }
        return dates;
    }

    private void secaproachDay(
            PrintStream out,
            io.github.cyonecoder.prayertime.application.provider.SecondsApproachPrayerTimesProvider provider,
            io.github.cyonecoder.prayertime.application.provider.HabousOfficialPrayerTimesProvider official,
            City city, LocalDate date, boolean seconds) {
        out.println("City:        " + city.displayName() + " (" + city.id() + ")");
        out.println("Subject:     " + date);
        out.println("Method:      Morocco Habous Sec-Approach (city-corrected)");
        out.println("Parameter:   " + io.github.cyonecoder.prayertime.application.provider
                .SecondsApproachPrayerTimesProvider.PARAMETER_VERSION);
        out.println();
        var official6 = official.officialTimes(city.id(), date);
        if (seconds) {
            out.printf("%-9s %11s%s%n", "Prayer", "Sec(raw)",
                    official6.isPresent() ? "   Official   Δpub" : "");
            for (Prayer p : Prayer.values()) {
                String off = official6.map(t -> "    " + timeOf(t, p).format(HM)
                        + String.format(" %+5d", diffPub(provider, official6.get(), city.id(), date, p)))
                        .orElse("");
                out.printf("%-9s %11s%s%n", p.name().toLowerCase(Locale.ROOT),
                        provider.secondPreciseTime(city.id(), date, p).format(HMS), off);
            }
        } else {
            PrayerTimes t = provider.computeTimes(city.id(), date);
            if (official6.isEmpty()) {
                printTimes(out, t, false);
            } else {
                out.printf("%-9s %9s %9s %6s%n", "Prayer", "SecApp", "Official", "Δ");
                for (Prayer p : Prayer.values()) {
                    out.printf("%-9s %9s %9s %+6d%n", p.name().toLowerCase(Locale.ROOT),
                            timeOf(t, p).format(HM), timeOf(official6.get(), p).format(HM),
                            minuteOf(timeOf(official6.get(), p)) - minuteOf(timeOf(t, p)));
                }
            }
        }
    }

    private void secaproachTable(
            PrintStream out,
            io.github.cyonecoder.prayertime.application.provider.SecondsApproachPrayerTimesProvider provider,
            io.github.cyonecoder.prayertime.application.provider.HabousOfficialPrayerTimesProvider official,
            City city, List<LocalDate> dates, boolean seconds) {
        DateTimeFormatter fmt = seconds ? HMS : HM;
        int w = seconds ? 8 : 6;
        out.println("City:        " + city.displayName() + " (" + city.id() + ")");
        out.println("Method:      Morocco Habous Sec-Approach (city-corrected)");
        out.println();
        out.printf("%-11s %" + w + "s %" + (w + 1) + "s %" + w + "s %" + w + "s %" + (w + 1)
                + "s %" + w + "s  %s%n",
                "Date", "Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha", "vs official");
        int comparedDays = 0;
        int exactDays = 0;
        for (LocalDate d : dates) {
            PrayerTimes t = provider.computeTimes(city.id(), d);
            String match;
            var off = official.officialTimes(city.id(), d);
            if (off.isEmpty()) {
                match = "-";
            } else {
                comparedDays++;
                int max = 0;
                for (Prayer p : Prayer.values()) {
                    max = Math.max(max, Math.abs(minuteOf(timeOf(off.get(), p)) - minuteOf(timeOf(t, p))));
                }
                if (max == 0) {
                    exactDays++;
                    match = "exact";
                } else {
                    match = "±" + max + "m";
                }
            }
            if (seconds) {
                out.printf("%-11s %" + w + "s %" + (w + 1) + "s %" + w + "s %" + w + "s %" + (w + 1)
                        + "s %" + w + "s  %s%n", d,
                        provider.secondPreciseTime(city.id(), d, Prayer.FAJR).format(HMS),
                        provider.secondPreciseTime(city.id(), d, Prayer.SUNRISE).format(HMS),
                        provider.secondPreciseTime(city.id(), d, Prayer.DHUHR).format(HMS),
                        provider.secondPreciseTime(city.id(), d, Prayer.ASR).format(HMS),
                        provider.secondPreciseTime(city.id(), d, Prayer.MAGHRIB).format(HMS),
                        provider.secondPreciseTime(city.id(), d, Prayer.ISHA).format(HMS), match);
            } else {
                out.printf("%-11s %" + w + "s %" + (w + 1) + "s %" + w + "s %" + w + "s %" + (w + 1)
                        + "s %" + w + "s  %s%n", d,
                        t.fajr().format(fmt), t.sunrise().format(fmt), t.dhuhr().format(fmt),
                        t.asr().format(fmt), t.maghrib().format(fmt), t.isha().format(fmt), match);
            }
        }
        if (comparedDays > 0) {
            out.println();
            out.printf(Locale.ROOT, "Matched official: %d/%d days exact (%.1f%%)%n",
                    exactDays, comparedDays, 100.0 * exactDays / comparedDays);
        }
    }

    private static int diffPub(
            io.github.cyonecoder.prayertime.application.provider.SecondsApproachPrayerTimesProvider provider,
            PrayerTimes official, String cityId, LocalDate date, Prayer p) {
        PrayerTimes t = provider.computeTimes(cityId, date);
        return minuteOf(timeOf(official, p)) - minuteOf(timeOf(t, p));
    }

    private static int minuteOf(java.time.LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }

    /**
     * {@code serve [--port <n>]}: starts the read-only HTTP API and blocks. Port
     * defaults to 8080; {@code 0} picks a free port.
     */
    private void serve(Map<String, String> flags, PrintStream out) {
        int port = Integer.parseInt(flags.getOrDefault("port", "8080"));
        var server = new io.github.cyonecoder.prayertime.application.provider.PrayerTimesHttpServer();
        try {
            int bound = server.start(port);
            out.println("Prayer-times API listening on http://localhost:" + bound
                    + "/api/v1/cities/{cityId}/prayer-times?date=YYYY-MM-DD&source=auto");
            Thread.currentThread().join();
        } catch (java.io.IOException e) {
            throw new CliException("could not start server: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            server.stop();
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
        out.println("  (calculate/range/month default to source=calculated = "
                + "Morocco Habous Reconstructed)");
        out.println("  calculate --city <c> --date <YYYY-MM-DD> "
                + "[--source calculated|official|auto|compare]");
        out.println("  range --city <c> --from <date> --to <date> "
                + "[--source calculated|official|auto|compare]");
        out.println("  month --city <c> --year <y> --month <m> "
                + "[--source calculated|official|auto|compare]");
        out.println("  clock --date <date>");
        out.println("  compare --city <c> --date <date>");
        out.println("  cities");
        out.println("  prayer-times --city <c> --date <date> "
                + "[--source auto|official|calculated|compare]   (JSON)");
        out.println("  secaproach --city <c> (--date <d> | --month <m> [--year <y>] "
                + "| --from <d> --to <d>) [--compare] [--seconds]   (city-corrected, ~97% exact)");
        out.println("  serve [--port <n>]");
        out.println("  (add --seconds to calculate/month/range/secaproach for HH:mm:ss)");
    }
}
