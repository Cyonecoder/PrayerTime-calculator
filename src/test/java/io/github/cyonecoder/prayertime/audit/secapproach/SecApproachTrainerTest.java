package io.github.cyonecoder.prayertime.audit.secapproach;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.optimization.HabousRawEngine;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachTrainer.Outcome;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The deterministic trainer over the committed research dataset: it reproduces
 * the shipped offsets byte-for-byte, preserves the 14 locked cities, gates the
 * below-band cities, and never touches a search boundary.
 */
class SecApproachTrainerTest {

    private static final Path DATASET = Path.of("research-data/habous");
    private static final Path BASE14 =
            Path.of("src/main/resources/research/morocco-habous-secaproach-base14.json");
    private static final Path COMMITTED =
            Path.of("src/main/resources/research/morocco-habous-secaproach.json");

    private SecApproachTrainingDataset dataset() {
        return SecApproachTrainingDataset.fromDirectory(
                DATASET, new MoroccoCityRegistry(), new HabousRawEngine());
    }

    private Map<String, Map<Prayer, Integer>> base14() {
        return SecApproachTrainingCommand.loadOffsets(BASE14);
    }

    @Test
    void preservesLockedCitiesGatesBelowBandAndAvoidsBoundaries() {
        Outcome outcome = new SecApproachTrainer(
                SecApproachOffsetSearch.DEFAULT_LOW, SecApproachOffsetSearch.DEFAULT_HIGH)
                .train(dataset(), base14());

        assertThat(outcome.preserved()).hasSize(14);
        // 34 new cities pass every per-city gate; 12 fail (all on the holdout gate).
        assertThat(outcome.written()).hasSize(34);
        assertThat(outcome.belowBand()).hasSize(12);
        assertThat(outcome.belowBand()).allSatisfy(c ->
                assertThat(c.gateFailures()).isNotEmpty());
        assertThat(outcome.offsets()).hasSize(48);
        assertThat(outcome.boundaryTouches()).as("no offset should sit on +/-900s").isEmpty();
        // Preserved cities keep their base offsets exactly.
        base14().forEach((city, off) -> assertThat(outcome.offsets().get(city)).isEqualTo(off));
    }

    @Test
    void isDeterministicAcrossRuns() {
        Outcome a = new SecApproachTrainer(-900, 900).train(dataset(), base14());
        Outcome b = new SecApproachTrainer(-900, 900).train(dataset(), base14());
        assertThat(a.offsets()).isEqualTo(b.offsets());
    }

    @Test
    void reproducesTheCommittedOffsetsFileByteForByte(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("regen.json");
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        new SecApproachTrainingCommand(new MoroccoCityRegistry(), new HabousRawEngine()).run(
                Map.of("dataset", DATASET.toString(),
                        "base", BASE14.toString(),
                        "offsets-out", out.toString(),
                        "report-out", tmp.resolve("report.json").toString()),
                new PrintStream(sink, true, StandardCharsets.UTF_8));

        assertThat(Files.readString(out, StandardCharsets.UTF_8))
                .isEqualTo(Files.readString(COMMITTED, StandardCharsets.UTF_8));
    }

    @Test
    void trainingObservationUsesRawSecondsNotRoundedMinute() {
        SecApproachTrainingDataset ds = dataset();
        var dhuhr = ds.observations("casablanca", Prayer.DHUHR);
        assertThat(dhuhr).hasSize(30);
        // Raw seconds are sub-minute precise: not every day lands on a whole minute
        // (proving we use raw seconds, not the already-rounded astronomical time).
        assertThat(dhuhr.stream().anyMatch(o -> o.rawSeconds() % 60 != 0)).isTrue();
        assertThat(dhuhr.get(0).snapshotChecksum()).isNotBlank();
        assertThat(dhuhr.get(0).split()).isNotBlank();
    }
}
