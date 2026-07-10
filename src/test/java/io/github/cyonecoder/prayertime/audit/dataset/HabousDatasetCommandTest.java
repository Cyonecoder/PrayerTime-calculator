package io.github.cyonecoder.prayertime.audit.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.Gson;
import io.github.cyonecoder.prayertime.audit.dataset.HabousTestFixtures.RecordingFetcher;
import io.github.cyonecoder.prayertime.audit.optimization.SearchCommand;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HabousDatasetCommandTest {

    private static final String CASA = "الدار البيضاء";

    private HabousDatasetCommand commandReturning(String html) {
        RecordingFetcher fetcher = new RecordingFetcher((id, d) -> html);
        return new HabousDatasetCommand(fetcher, duration -> { }, // no-op sleeper
                Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC));
    }

    private PrintStream sink() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    @Test
    void parseMonthsHandlesRangesListsAndSingles() {
        assertThat(HabousDatasetCommand.parseMonths("1-12"))
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        assertThat(HabousDatasetCommand.parseMonths("1,3,5")).containsExactly(1, 3, 5);
        assertThat(HabousDatasetCommand.parseMonths("7")).containsExactly(7);
        assertThatThrownBy(() -> HabousDatasetCommand.parseMonths("13"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fetchWritesSnapshotsAndCoverageReport(@TempDir Path dataset, @TempDir Path report) {
        Map<String, String> flags = new LinkedHashMap<>();
        flags.put("cities", "casablanca");
        flags.put("year", "2026");
        flags.put("months", "7");
        flags.put("output", dataset.toString());
        flags.put("report", report.toString());
        flags.put("delay-seconds", "0");

        commandReturning(HabousTestFixtures.monthHtml(CASA, 2026, 7)).fetch(flags, sink());

        HabousSnapshotStore store = new HabousSnapshotStore(dataset);
        assertThat(store.primaryExists("casablanca", 2026, 7)).isTrue();
        assertThat(report.resolve("coverage.json")).exists();
        assertThat(report.resolve("coverage.md")).exists();
        assertThat(report.resolve("missing-cities.csv")).exists();
        assertThat(report.resolve("missing-months.csv")).exists();
        assertThat(report.resolve("invalid-pages.csv")).exists();
        assertThat(report.resolve("duplicate-snapshots.csv")).exists();
        assertThat(report.resolve("checksum-history.csv")).exists();
    }

    @Test
    @SuppressWarnings("unchecked")
    void coverageJsonReflectsDownloadedObservations(@TempDir Path dataset, @TempDir Path report)
            throws Exception {
        Map<String, String> flags = new LinkedHashMap<>();
        flags.put("cities", "casablanca");
        flags.put("year", "2026");
        flags.put("months", "7");
        flags.put("output", dataset.toString());
        flags.put("report", report.toString());
        flags.put("delay-seconds", "0");
        commandReturning(HabousTestFixtures.monthHtml(CASA, 2026, 7)).fetch(flags, sink());

        Map<String, Object> coverage = new Gson().fromJson(
                Files.readString(report.resolve("coverage.json"), StandardCharsets.UTF_8), Map.class);
        assertThat(((Number) coverage.get("downloadedCities")).intValue()).isEqualTo(1);
        assertThat(((Number) coverage.get("availableMonths")).intValue()).isEqualTo(1);
        // 31 days x 6 prayers.
        assertThat(((Number) coverage.get("totalPrayerObservations")).intValue()).isEqualTo(186);
    }

    @Test
    void datasetCoverageSubcommandRoutesThroughResearchVerb(@TempDir Path dataset, @TempDir Path report) {
        // First populate a snapshot.
        Map<String, String> flags = new LinkedHashMap<>();
        flags.put("cities", "casablanca");
        flags.put("year", "2026");
        flags.put("months", "7");
        flags.put("output", dataset.toString());
        flags.put("report", report.toString());
        flags.put("delay-seconds", "0");
        commandReturning(HabousTestFixtures.monthHtml(CASA, 2026, 7)).fetch(flags, sink());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = new SearchCommand().run(new String[]{
                "research", "dataset-coverage",
                "--dataset", dataset.toString(),
                "--year", "2026", "--months", "1-12",
                "--output", report.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8), sink());

        assertThat(exit).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Coverage report written");
    }

    @Test
    void allMappedFetchesEveryMappedCity(@TempDir Path dataset, @TempDir Path report) {
        Map<String, String> flags = new LinkedHashMap<>();
        flags.put("cities", "all-mapped");
        flags.put("year", "2026");
        flags.put("months", "7");
        flags.put("output", dataset.toString());
        flags.put("report", report.toString());
        flags.put("delay-seconds", "0");

        // The wrong-city check keys on the Arabic name, so the responder maps the
        // requested ville id back to its Arabic name and builds a matching page.
        Map<String, String> arabicByVille = new LinkedHashMap<>();
        HabousCityMapping.load().all()
                .forEach(e -> arabicByVille.put(e.habousCityId(), e.habousArabicName()));
        RecordingFetcher fetcher = new RecordingFetcher(
                (habousCityId, d) -> HabousTestFixtures.monthHtml(arabicByVille.get(habousCityId), 2026, 7));
        new HabousDatasetCommand(fetcher, duration -> { },
                Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC)).fetch(flags, sink());

        HabousSnapshotStore store = new HabousSnapshotStore(dataset);
        List<HabousSnapshotStore.StoredSnapshot> all = store.scanAll();
        assertThat(all).hasSize(HabousCityMapping.load().mappedCityIds().size());
    }
}
