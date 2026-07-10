package io.github.cyonecoder.prayertime.audit.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.model.GlobalModelSelection.SelectionResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The report writer must emit all eight deliverables with the required content. */
class GlobalModelReportTest {

    private SelectionResult run() {
        return GlobalModelFixtures.selection().run(
                GlobalModelFixtures.realDataset(),
                GlobalModelFixtures.FROZEN_AT, GlobalModelFixtures.COMMIT);
    }

    @Test
    void writesAllEightDeliverables(@TempDir Path out) throws Exception {
        new GlobalModelReportWriter().write(out, run());
        for (String file : GlobalModelReportWriter.FILES) {
            Path path = out.resolve(file);
            assertThat(Files.exists(path)).as("exists: %s", file).isTrue();
            assertThat(Files.size(path)).as("non-empty: %s", file).isGreaterThan(0L);
        }
    }

    @Test
    void residualAnalysisReportCoversEveryRequiredAxis(@TempDir Path out) throws Exception {
        new GlobalModelReportWriter().write(out, run());
        String md = Files.readString(out.resolve("residual-analysis.md"));
        assertThat(md)
                .contains("Residuals by prayer")
                .contains("Residuals by city")
                .contains("Residuals by month")
                .contains("Residuals by latitude")
                .contains("Residuals by longitude")
                .contains("Residuals by elevation")
                .contains("Residuals by UTC offset")
                .contains("Signed-error frequency")
                .contains("Abrupt official date changes")
                .contains("Detections");
    }

    @Test
    void holdoutOutputsReflectThePresentHoldoutPartition(@TempDir Path out) throws Exception {
        new GlobalModelReportWriter().write(out, run());
        // The live dataset contains the holdout cities, so the holdout is scored
        // once after the freeze: the CSV carries real residual rows and
        // reproducibility.json records that the holdout was evaluated.
        String holdoutCsv = Files.readString(out.resolve("holdout-results.csv"));
        assertThat(holdoutCsv).doesNotContain("no holdout observations present");
        assertThat(holdoutCsv).contains("dakhla");
        assertThat(Files.readString(out.resolve("reproducibility.json")))
                .contains("\"holdoutEvaluated\": true");
    }

    @Test
    void modelComparisonListsEveryFamily(@TempDir Path out) throws Exception {
        new GlobalModelReportWriter().write(out, run());
        String csv = Files.readString(out.resolve("model-comparison.csv"));
        for (GlobalModelFamily family : GlobalModelFamily.values()) {
            assertThat(csv).as("family %s listed", family).contains(family.name());
        }
    }
}
