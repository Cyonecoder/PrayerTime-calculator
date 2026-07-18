package io.github.cyonecoder.salatapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.cyonecoder.salatapi.archive.io.AtomicJsonFileWriter;
import io.github.cyonecoder.salatapi.archive.repository.JsonMonthlyArchiveRepository;
import io.github.cyonecoder.salatapi.common.checksum.Sha256Service;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The offline filesystem startup steps (spec 27): create data directories and
 * validate archive manifest checksums, without any network access.
 */
class StartupIntegrityRunnerTest {

    @TempDir
    Path dataDir;

    private StartupIntegrityRunner runner;
    private SalatApiProperties properties;
    private JsonMonthlyArchiveRepository archives;

    @BeforeEach
    void setUp() {
        properties = new SalatApiProperties("Africa/Casablanca", dataDir);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var jsonFiles = new AtomicJsonFileWriter(mapper);
        var sha = new Sha256Service();
        archives = new JsonMonthlyArchiveRepository(properties, jsonFiles, sha);
        runner = new StartupIntegrityRunner(properties, archives, sha);
    }

    @Test
    void everyRuntimeDataDirectoryIsCreated() {
        runner.createDataDirectories();

        assertThat(properties.catalogDirectory()).isDirectory();
        assertThat(properties.catalogHistoryDirectory()).isDirectory();
        assertThat(properties.rawDirectory()).isDirectory();
        assertThat(properties.archivesDirectory()).isDirectory();
        assertThat(properties.verificationDailyDirectory()).isDirectory();
        assertThat(properties.jobsDirectory()).isDirectory();
        assertThat(properties.stagingDirectory()).isDirectory();
        assertThat(properties.rejectedDirectory()).isDirectory();
    }

    @Test
    void anEmptyDataDirectoryValidatesToZeroArchives() {
        runner.createDataDirectories();
        assertThat(runner.validateArchives()).isZero();
    }

    @Test
    void aCorruptArchiveIsReportedButDoesNotStopStartup() throws Exception {
        // A manifest exists but the archive file was tampered with after the fact.
        runner.createDataDirectories();
        Path monthDir = properties.archiveDirectory(java.time.YearMonth.of(2026, 7));
        Files.createDirectories(monthDir);
        Files.writeString(monthDir.resolve("habous-prayer-times-2026-07.json"), "{\"tampered\":true}");
        Files.writeString(monthDir.resolve("manifest.json"),
                "{\"schemaVersion\":\"1.0\",\"period\":\"2026-07\",\"status\":\"PARTIAL\","
                        + "\"archiveFile\":\"habous-prayer-times-2026-07.json\","
                        + "\"archiveSha256\":\"deadbeef\",\"officialCatalogCityCount\":191,"
                        + "\"archiveCityCount\":0,\"completeCityCount\":0,\"partialCityCount\":0,"
                        + "\"unavailableCityCount\":0,\"expectedDaysPerCompleteCity\":31,"
                        + "\"parserVersion\":\"habous-live-1\",\"villeParserVersion\":\"habous-ville-select-1\","
                        + "\"warnings\":[],\"failedCities\":[]}");

        // Validation completes (does not throw); the mismatch is a warning only.
        assertThat(runner.validateArchives()).isEqualTo(1);
    }
}
