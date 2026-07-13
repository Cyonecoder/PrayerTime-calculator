package io.github.cyonecoder.prayertime.audit.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient.Fetcher;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end wiring for {@code research verify-habous-cities} with a fixture
 * fetcher (no network): it parses the dropdown, verifies candidates, and writes
 * the options audit, the verification report, and a verified-only mapping.
 */
class HabousCityVerificationCommandTest {

    private static final Path FIXTURE =
            Path.of("src/test/resources/fixtures/habous/horaire-rabat-live-2026-muharram.html");

    private String fixture() throws IOException {
        return Files.readString(FIXTURE, StandardCharsets.UTF_8);
    }

    @Test
    void writesAuditReportAndVerifiedOnlyMapping(@TempDir Path tmp) throws IOException {
        Fetcher fixtureFetcher = (id, date) -> {
            try {
                return fixture();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        Clock clock = Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC);

        Path mapping = tmp.resolve("mapping.json");
        Files.copy(Path.of("src/main/resources/research/habous-city-mapping.json"), mapping);
        Path optionsOut = tmp.resolve("options.json");
        Path reportOut = tmp.resolve("verification.json");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new HabousCityVerificationCommand(fixtureFetcher, clock).run(
                Map.of("delay-seconds", "0",
                        "mapping", mapping.toString(),
                        "options-out", optionsOut.toString(),
                        "report-out", reportOut.toString()),
                new PrintStream(baos, true, StandardCharsets.UTF_8));

        // Options audit: the whole dropdown with provenance.
        String options = Files.readString(optionsOut, StandardCharsets.UTF_8);
        assertThat(options).contains("\"parserVersion\": \"habous-ville-select-1\"", "\"options\"");

        // Verification report: a status for every one of the 67 candidates.
        String report = Files.readString(reportOut, StandardCharsets.UTF_8);
        assertThat(report).contains("\"verificationMethod\": \"live-select-and-timetable\"",
                "\"total\": 67");

        // Rewritten mapping: verified-only, carrying provenance, still runtime-loadable.
        String mappingJson = Files.readString(mapping, StandardCharsets.UTF_8);
        assertThat(mappingJson).contains("\"verified\": true", "\"verificationMethod\"",
                "\"sourceChecksum\"");
        assertThat(mappingJson).doesNotContain("\"verified\": false");

        String stdout = baos.toString(StandardCharsets.UTF_8);
        assertThat(stdout).contains("VERIFIED", "Status summary:");
    }
}
