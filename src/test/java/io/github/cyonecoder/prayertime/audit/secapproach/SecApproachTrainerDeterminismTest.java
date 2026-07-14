package io.github.cyonecoder.prayertime.audit.secapproach;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.optimization.HabousRawEngine;
import io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The trainer and its outputs are byte-for-byte reproducible for a fixed dataset. */
class SecApproachTrainerDeterminismTest {

    private static final Path DATASET = Path.of("research-data/habous");
    private static final Path BASE14 =
            Path.of("src/main/resources/research/morocco-habous-secaproach-base14.json");

    private SecApproachTrainingDataset dataset() {
        return SecApproachTrainingDataset.fromDirectory(
                DATASET, new MoroccoCityRegistry(), new HabousRawEngine());
    }

    @Test
    void offsetsAreIdenticalAcrossRuns() {
        Map<String, Map<io.github.cyonecoder.prayertime.domain.model.Prayer, Integer>> base =
                SecApproachTrainingCommand.loadOffsets(BASE14);
        var a = new SecApproachTrainer(-900, 900).train(dataset(), base).offsets();
        var b = new SecApproachTrainer(-900, 900).train(dataset(), base).offsets();
        assertThat(a).isEqualTo(b);
    }

    @Test
    void offsetsReportAndHoldoutFilesAreByteForByteStable(@TempDir Path tmp) throws Exception {
        String[] first = runToTemp(tmp, "a");
        String[] second = runToTemp(tmp, "b");
        assertThat(first[0]).isEqualTo(second[0]); // offsets
        assertThat(first[1]).isEqualTo(second[1]); // training report
        assertThat(first[2]).isEqualTo(second[2]); // holdout report
    }

    private String[] runToTemp(Path tmp, String tag) throws Exception {
        Path offsets = tmp.resolve(tag + "-offsets.json");
        Path report = tmp.resolve(tag + "-report.json");
        Path holdout = tmp.resolve(tag + "-holdout.json");
        new SecApproachTrainingCommand(new MoroccoCityRegistry(), new HabousRawEngine()).run(
                Map.of("dataset", DATASET.toString(), "base", BASE14.toString(),
                        "offsets-out", offsets.toString(), "report-out", report.toString(),
                        "holdout-out", holdout.toString()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        return new String[] {
            Files.readString(offsets, StandardCharsets.UTF_8),
            Files.readString(report, StandardCharsets.UTF_8),
            Files.readString(holdout, StandardCharsets.UTF_8)
        };
    }

    @Test
    void reproducesTheCommittedResourceByteForByte(@TempDir Path tmp) throws Exception {
        Path offsets = tmp.resolve("offsets.json");
        new SecApproachTrainingCommand(new MoroccoCityRegistry(), new HabousRawEngine()).run(
                Map.of("dataset", DATASET.toString(), "base", BASE14.toString(),
                        "offsets-out", offsets.toString(),
                        "report-out", tmp.resolve("r.json").toString(),
                        "holdout-out", tmp.resolve("h.json").toString()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertThat(Files.readString(offsets, StandardCharsets.UTF_8)).isEqualTo(
                Files.readString(Path.of("src/main/resources/research/morocco-habous-secaproach.json"),
                        StandardCharsets.UTF_8));
    }
}
