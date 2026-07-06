package io.github.cyonecoder.prayertime.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.application.service.PrayerTimeComparisonService;
import io.github.cyonecoder.prayertime.domain.calculation.PrayerTimeCalculator;
import io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousCache;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient.HabousUnavailableException;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousHtmlParser;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousPrayerTimesProvider;
import io.github.cyonecoder.prayertime.time.MoroccoCivilTimeService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MainTest {

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    // A Habous provider that would fail if ever asked to fetch (guarantees no network).
    private final Main cli = new Main(
            new MoroccoCityRegistry(),
            new PrayerTimeCalculator(),
            new MoroccoCivilTimeService(),
            new PrayerTimeComparisonService(),
            new HabousPrayerTimesProvider(
                    (id, date) -> {
                        throw new HabousUnavailableException("no network in tests");
                    },
                    new HabousHtmlParser(),
                    new HabousCache()));

    private int run(String... args) {
        return cli.run(args, out, err);
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void calculatePrintsFullHeaderAndSixTimes() {
        int code = run("calculate", "--city", "rabat", "--date", "2026-07-03");

        assertThat(code).isZero();
        String o = out();
        assertThat(o).contains("Rabat", "2026-07-03", "Africa/Casablanca", "+01:00",
                "Morocco (Habous)", "astronomical");
        assertThat(o).contains("Fajr:", "Sunrise:", "Dhuhr:", "Asr:", "Maghrib:", "Isha:");
    }

    @Test
    void invalidCityFailsWithUsageExitAndNoStackTrace() {
        int code = run("calculate", "--city", "atlantis", "--date", "2026-07-03");

        assertThat(code).isEqualTo(2);
        assertThat(err()).contains("unknown city: atlantis");
        assertThat(err()).doesNotContain("Exception", "\tat ");
    }

    @Test
    void invalidDateFailsWithUsageExit() {
        int code = run("calculate", "--city", "rabat", "--date", "not-a-date");

        assertThat(code).isEqualTo(2);
        assertThat(err()).contains("invalid date");
        assertThat(err()).doesNotContain("\tat ");
    }

    @Test
    void rangePrintsEachDay() {
        int code = run("range", "--city", "rabat", "--from", "2026-07-03", "--to", "2026-07-05");

        assertThat(code).isZero();
        assertThat(out()).contains("2026-07-03", "2026-07-04", "2026-07-05");
    }

    @Test
    void clockShowsModeAndOffset() {
        int code = run("clock", "--date", "2026-07-03");

        assertThat(code).isZero();
        assertThat(out()).contains("UTC_PLUS_ONE", "+01:00", "Africa/Casablanca");
    }

    @Test
    void clockShowsRamadanSuspensionMode() {
        int code = run("clock", "--date", "2026-03-01");

        assertThat(code).isZero();
        assertThat(out()).contains("UTC_ZERO_RAMADAN_SUSPENSION", "+00:00");
    }

    @Test
    void compareReportsUnavailableHabousWithoutSubstitution() {
        // Registry cities carry no Habous ID, so official data is unavailable.
        int code = run("compare", "--city", "rabat", "--date", "2026-07-03");

        assertThat(code).isZero();
        String o = out();
        assertThat(o).contains("UNAVAILABLE");
        assertThat(o).contains("not substituted");
    }

    @Test
    void citiesListsRegistry() {
        int code = run("cities");

        assertThat(code).isZero();
        assertThat(out()).contains("rabat", "casablanca", "tangier");
    }

    @Test
    void unknownCommandIsUsageError() {
        assertThat(run("frobnicate")).isEqualTo(2);
        assertThat(err()).contains("unknown command");
    }
}
