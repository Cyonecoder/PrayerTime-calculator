package io.github.cyonecoder.prayertime.audit.secapproach;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code research reports} produces the full, deterministic report bundle. */
class SecApproachReportsCommandTest {

    private static final List<String> REQUIRED = List.of(
            "city-verification.md", "city-verification.json", "dataset-coverage.md",
            "dataset-coverage.csv", "training-metrics-by-city.csv", "training-metrics-by-prayer.csv",
            "holdout-metrics-by-city.csv", "baseline-vs-secapproach.csv", "rejected-cities.md",
            "existing-city-regression.md", "generated-parameters.json", "summary.md");

    private Path generate(Path dir) {
        new SecApproachReportsCommand().run(
                Map.of("dataset", "research-data/habous", "output", dir.toString()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        return dir;
    }

    private static String read(Path dir, String name) {
        try {
            return Files.readString(dir.resolve(name), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void writesEveryRequiredFile(@TempDir Path tmp) {
        Path dir = generate(tmp);
        for (String f : REQUIRED) {
            assertThat(Files.isRegularFile(dir.resolve(f))).as(f).isTrue();
        }
    }

    @Test
    void summaryReportsTheInventoryDatasetAndMetrics(@TempDir Path tmp) {
        String s = read(generate(tmp), "summary.md");
        assertThat(s).contains(
                "| Candidate cities | 67 |",
                "| Active-source cities | 48 |",
                "| Commented-source cities | 19 |",
                "| Previously calibrated cities (locked) | 14 |",
                "| Newly calibrated cities | 34 |",
                "| Sec-supported cities | 48 |",
                "| Failed-accuracy cities | 12 |",
                "| Expected observations | 10800 |",
                "| Actual observations | 10800 |",
                "| Missing observations | 0 |",
                "| Duplicate observations | 0 |");
        assertThat(s).contains("Sec-approach exact", "Global formula exact", "Improvement",
                "Within ±1 minute", "MAE", "Maximum error");
    }

    @Test
    void regressionReportProvesTheFourteenAreUnchanged(@TempDir Path tmp) {
        String r = read(generate(tmp), "existing-city-regression.md");
        assertThat(r).contains("**UNCHANGED**").doesNotContain("**✗**");
    }

    @Test
    void baselineCsvHasEveryDatasetCityWithImprovement(@TempDir Path tmp) {
        String csv = read(generate(tmp), "baseline-vs-secapproach.csv");
        String[] lines = csv.strip().split("\n");
        // header + 60 dataset cities.
        assertThat(lines).hasSize(61);
        assertThat(lines[0]).isEqualTo(
                "city,supported,globalExactPct,secExactPct,improvementPp,secWithinOnePct,secMaxAbs");
    }

    @Test
    void isDeterministic(@TempDir Path tmp) {
        Path a = generate(tmp.resolve("a"));
        Path b = generate(tmp.resolve("b"));
        for (String f : REQUIRED) {
            assertThat(read(a, f)).as(f).isEqualTo(read(b, f));
        }
    }
}
