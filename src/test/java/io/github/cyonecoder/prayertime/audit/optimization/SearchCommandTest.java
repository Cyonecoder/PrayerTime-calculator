package io.github.cyonecoder.prayertime.audit.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchCommandTest {

    private record Run(int exitCode, String out, String err) {
    }

    private Run invoke(String... args) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = new SearchCommand().run(args,
                new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        return new Run(code, outBuf.toString(StandardCharsets.UTF_8), errBuf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void searchParameterWritesAllSixReportFiles(@TempDir Path out) {
        Run run = invoke("research", "search-parameter",
                "--prayer", "sunrise",
                "--dataset", "research-data/habous",
                "--cities", "casablanca",
                "--from", "2026-07-01", "--to", "2026-07-31",
                "--output", out.toString());

        assertThat(run.exitCode()).isZero();
        assertThat(out.resolve("all-candidates.csv")).exists();
        assertThat(out.resolve("top-candidates.json")).exists();
        assertThat(out.resolve("top-candidates.md")).exists();
        assertThat(out.resolve("exact-match-intervals.csv")).exists();
        assertThat(out.resolve("residual-distribution.csv")).exists();
        assertThat(out.resolve("search-metadata.json")).exists();
        assertThat(run.out()).contains("Best equivalent interval");
    }

    @Test
    void rankCandidatesReReadsAndRanksTheWrittenCsv(@TempDir Path out) {
        invoke("research", "search-parameter", "--prayer", "sunrise",
                "--dataset", "research-data/habous", "--cities", "casablanca",
                "--output", out.toString());

        Run ranked = invoke("research", "rank-candidates", "--input", out.toString(), "--top", "3");
        assertThat(ranked.exitCode()).isZero();
        assertThat(ranked.out()).contains("Re-ranked");
    }

    @Test
    void searchRoundingEvaluatesEveryPolicyAndOrder(@TempDir Path out) {
        Run run = invoke("research", "search-rounding",
                "--dataset", "research-data/habous", "--cities", "casablanca",
                "--output", out.toString());
        assertThat(run.exitCode()).isZero();
        // 6 policies x 3 operation orders.
        assertThat(run.out()).contains("Evaluated 18");
    }

    @Test
    void unknownSubcommandIsAUsageError() {
        Run run = invoke("research", "frobnicate");
        assertThat(run.exitCode()).isEqualTo(2);
        assertThat(run.err()).contains("unknown research subcommand");
    }

    @Test
    void missingRequiredFlagIsAUsageError() {
        Run run = invoke("research", "search-parameter", "--dataset", "research-data/habous");
        assertThat(run.exitCode()).isEqualTo(2);
        assertThat(run.err()).contains("prayer");
    }

    @Test
    void prayerAliasesResolve() {
        assertThat(SearchCommand.parsePrayer("chourouk")).isEqualTo(Prayer.SUNRISE);
        assertThat(SearchCommand.parsePrayer("Maghreb")).isEqualTo(Prayer.MAGHRIB);
        assertThat(SearchCommand.parsePrayer("dohr")).isEqualTo(Prayer.DHUHR);
    }
}
