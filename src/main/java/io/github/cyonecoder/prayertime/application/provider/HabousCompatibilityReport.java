package io.github.cyonecoder.prayertime.application.provider;

import io.github.cyonecoder.prayertime.audit.dataset.HabousCityMapping;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Computes the Habous compatibility metrics enforced in CI and writes the
 * associated reports: reconstructed-formula accuracy, official-mode exact
 * agreement, mapped-city coverage, and the list of unresolved official
 * mismatches (which must be empty).
 */
public final class HabousCompatibilityReport {

    /** One official-mode disagreement (must never occur). */
    public record OfficialMismatch(
            String cityId, LocalDate date, Prayer prayer, String official, String served) {
    }

    /** The computed metrics. */
    public record Metrics(
            int formulaObservations,
            double formulaExactPercentage,
            double formulaWithinOnePercentage,
            double formulaMae,
            int formulaMaxErrorMinutes,
            int officialObservations,
            double officialExactPercentage,
            List<OfficialMismatch> officialMismatches,
            List<String> mappedCities,
            List<String> supportedCities,
            List<String> unmappedOrUncovered) {

        public boolean officialExact() {
            return officialMismatches.isEmpty()
                    && officialObservations > 0
                    && officialExactPercentage == 100.0;
        }

        public boolean cityCoverageComplete() {
            return unmappedOrUncovered.isEmpty();
        }
    }

    private final OfficialHabousStore store;
    private final HabousOfficialPrayerTimesProvider official;
    private final AstronomicalPrayerTimesProvider calculated;
    private final HabousCityMapping mapping;

    public HabousCompatibilityReport() {
        this(new OfficialHabousStore(), new AstronomicalPrayerTimesProvider(), HabousCityMapping.load());
    }

    public HabousCompatibilityReport(
            OfficialHabousStore store, AstronomicalPrayerTimesProvider calculated,
            HabousCityMapping mapping) {
        this.store = Objects.requireNonNull(store, "store");
        this.official = new HabousOfficialPrayerTimesProvider(store);
        this.calculated = Objects.requireNonNull(calculated, "calculated");
        this.mapping = Objects.requireNonNull(mapping, "mapping");
    }

    /** Computes all metrics over the bundled official data. */
    public Metrics compute() {
        int fN = 0;
        int fExact = 0;
        int fWithin = 0;
        int fMax = 0;
        long fSumAbs = 0;
        int oN = 0;
        int oExact = 0;
        List<OfficialMismatch> mismatches = new ArrayList<>();

        for (String cityId : store.supportedCityIds()) {
            OfficialSnapshot s = store.snapshot(cityId).orElseThrow();
            for (LocalDate date : s.dates()) {
                PrayerTimes off = s.day(date).orElseThrow();
                PrayerTimes calc = calculated.computeTimes(cityId, date);
                PrayerTimes served = official.officialTimes(cityId, date).orElseThrow();
                for (Prayer p : Prayer.values()) {
                    int offMin = min(OfficialSnapshot.timeFor(off, p));
                    int calcMin = min(OfficialSnapshot.timeFor(calc, p));
                    int servedMin = min(OfficialSnapshot.timeFor(served, p));

                    int d = Math.abs(offMin - calcMin);
                    if (d == 0) {
                        fExact++;
                    }
                    if (d <= 1) {
                        fWithin++;
                    }
                    fMax = Math.max(fMax, d);
                    fSumAbs += d;
                    fN++;

                    oN++;
                    if (offMin == servedMin) {
                        oExact++;
                    } else {
                        mismatches.add(new OfficialMismatch(cityId, date, p,
                                OfficialSnapshot.timeFor(off, p).toString(),
                                OfficialSnapshot.timeFor(served, p).toString()));
                    }
                }
            }
        }

        // Production OFFICIAL coverage is measured against the hand-verified set;
        // research-only (verified=false) mappings have no bundled production snapshot.
        List<String> mapped = new ArrayList<>(new TreeSet<>(mapping.verifiedCityIds()));
        List<String> supported = store.supportedCityIds();
        List<String> gap = new ArrayList<>();
        for (String c : mapped) {
            if (!supported.contains(c)) {
                gap.add(c);
            }
        }

        return new Metrics(fN,
                pct(fExact, fN), pct(fWithin, fN), fN == 0 ? 0 : (double) fSumAbs / fN, fMax,
                oN, pct(oExact, oN), List.copyOf(mismatches), mapped, supported, gap);
    }

    /**
     * Writes the report files and returns the metrics.
     *
     * @param outputDir the directory to write into (created if missing)
     * @return the computed metrics
     */
    public Metrics write(Path outputDir) {
        Metrics m = compute();
        try {
            Files.createDirectories(outputDir);
            Files.writeString(outputDir.resolve("formula-accuracy.txt"), formulaText(m),
                    StandardCharsets.UTF_8);
            Files.writeString(outputDir.resolve("official-compatibility.txt"), officialText(m),
                    StandardCharsets.UTF_8);
            Files.writeString(outputDir.resolve("city-coverage.txt"), coverageText(m),
                    StandardCharsets.UTF_8);
            Files.writeString(outputDir.resolve("unresolved-differences.csv"), unresolvedCsv(m),
                    StandardCharsets.UTF_8);
            Files.writeString(outputDir.resolve("summary.md"), summaryMarkdown(m),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("writing compatibility reports", e);
        }
        return m;
    }

    /** The human-readable final-verification summary required by Prompt 8. */
    public String summary(Metrics m) {
        return String.format(Locale.ROOT,
                "Formula exact-match percentage: %.2f%%%n"
                        + "Formula MAE: %.3f%n"
                        + "Formula maximum error: %d min%n"
                        + "Official-mode exact-match percentage: %.2f%%%n"
                        + "Mapped city coverage: %d/%d%n"
                        + "Unresolved official mismatches: %d",
                m.formulaExactPercentage(), m.formulaMae(), m.formulaMaxErrorMinutes(),
                m.officialExactPercentage(), m.supportedCities().size(), m.mappedCities().size(),
                m.officialMismatches().size());
    }

    private String formulaText(Metrics m) {
        return String.format(Locale.ROOT,
                "Reconstructed formula (MOROCCO_HABOUS_RECONSTRUCTED) vs official%n"
                        + "observations: %d%nexact: %.2f%%%nwithin-1-min: %.2f%%%nMAE: %.3f%nmax-error: %d min%n",
                m.formulaObservations(), m.formulaExactPercentage(), m.formulaWithinOnePercentage(),
                m.formulaMae(), m.formulaMaxErrorMinutes());
    }

    private String officialText(Metrics m) {
        return String.format(Locale.ROOT,
                "Official-mode exact agreement%nobservations: %d%nexact: %.2f%%%nmismatches: %d%nPASS: %s%n",
                m.officialObservations(), m.officialExactPercentage(),
                m.officialMismatches().size(), m.officialExact());
    }

    private String coverageText(Metrics m) {
        return "Mapped cities: " + m.mappedCities() + System.lineSeparator()
                + "Supported (bundled+valid): " + m.supportedCities() + System.lineSeparator()
                + "Missing coverage: " + m.unmappedOrUncovered() + System.lineSeparator();
    }

    private String unresolvedCsv(Metrics m) {
        StringBuilder sb = new StringBuilder("cityId,date,prayer,official,served\n");
        for (OfficialMismatch x : m.officialMismatches()) {
            sb.append(x.cityId()).append(',').append(x.date()).append(',').append(x.prayer())
                    .append(',').append(x.official()).append(',').append(x.served()).append('\n');
        }
        return sb.toString();
    }

    private String summaryMarkdown(Metrics m) {
        return "# Habous compatibility summary\n\n```\n" + summary(m) + "\n```\n\n"
                + "Official-mode exact-match must be 100% with 0 unresolved mismatches.\n";
    }

    private static double pct(int num, int den) {
        return den == 0 ? 0.0 : 100.0 * num / den;
    }

    private static int min(java.time.LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }
}
