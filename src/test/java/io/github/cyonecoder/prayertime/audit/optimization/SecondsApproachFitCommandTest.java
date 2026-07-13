package io.github.cyonecoder.prayertime.audit.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.optimization.SecondsApproachFitCommand.Outcome;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code research fit-secaproach} must regenerate a valid, loadable offsets file
 * from the official research dataset, verify-gate each city, and stay deterministic.
 */
class SecondsApproachFitCommandTest {

    private static final Path DATASET = Path.of("research-data/habous");

    private final SecondsApproachFitCommand command = new SecondsApproachFitCommand();

    @Test
    void writesEveryDatasetCityAboveTheDefaultBand(@TempDir Path tmp) {
        ReferenceDataset dataset = ReferenceDataset.load(DATASET);
        Outcome outcome = command.evaluate(dataset, SecondsApproachFitCommand.DEFAULT_MIN_EXACT);

        // 60 verified cities: 56 clear the band, 4 fall below (berrechid,
        // fkih-ben-salah, larache, sidi-kacem) and are reported, not written.
        assertThat(outcome.written()).hasSize(56);
        assertThat(outcome.rejected()).hasSize(4);
        assertThat(outcome.unresolved()).isEmpty();
        assertThat(outcome.written()).allSatisfy(fit ->
                assertThat(fit.exactRatio()).isGreaterThanOrEqualTo(0.95));
    }

    @Test
    void gateExcludesCitiesBelowTheBand() {
        ReferenceDataset dataset = ReferenceDataset.load(DATASET);
        // An impossibly high bar rejects every city; none crash, all are reported.
        Outcome outcome = command.evaluate(dataset, 1.01 - 0.0);
        assertThat(outcome.written()).isEmpty();
        assertThat(outcome.rejected()).hasSize(60);
    }

    @Test
    void writtenFileIsValidLoadableJsonInTheCommittedSchema(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("morocco-habous-secaproach.json");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        command.run(Map.of("dataset", DATASET.toString(), "output", out.toString()),
                new PrintStream(baos, true, StandardCharsets.UTF_8));

        String json = Files.readString(out, StandardCharsets.UTF_8);
        assertThat(json)
                .contains("\"parameterVersion\": \"morocco-habous-secaproach-v1\"")
                .contains("\"offsets\"")
                .contains("\"casablanca\"")
                .contains("\"fajr\":");

        // Regenerating is byte-stable (deterministic writer + fitter).
        Path out2 = tmp.resolve("again.json");
        command.run(Map.of("dataset", DATASET.toString(), "output", out2.toString()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertThat(Files.readString(out2, StandardCharsets.UTF_8)).isEqualTo(json);
    }

    @Test
    void baseCitiesArePreservedVerbatim(@TempDir Path tmp) throws Exception {
        Path committed = Path.of("src/main/resources/research/morocco-habous-secaproach.json");
        Path out = tmp.resolve("preserved.json");
        command.run(
                Map.of("dataset", DATASET.toString(), "base", committed.toString(),
                        "output", out.toString()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        // Every base city keeps its exact committed offsets (production output unchanged),
        // even though the free fitter would pick slightly different (equally-good) values.
        var baseOffsets = SecondsApproachFitCommand.loadOffsets(committed);
        var writtenOffsets = SecondsApproachFitCommand.loadOffsets(out);
        assertThat(writtenOffsets).containsAllEntriesOf(baseOffsets);
    }
}
