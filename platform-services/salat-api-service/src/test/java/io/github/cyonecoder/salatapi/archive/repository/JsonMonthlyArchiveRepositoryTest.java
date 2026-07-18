package io.github.cyonecoder.salatapi.archive.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.cyonecoder.salatapi.archive.io.AtomicJsonFileWriter;
import io.github.cyonecoder.salatapi.archive.model.ArchiveFailureCode;
import io.github.cyonecoder.salatapi.archive.model.ArchivedDay;
import io.github.cyonecoder.salatapi.archive.model.CityArchiveStatus;
import io.github.cyonecoder.salatapi.archive.model.GregorianMonthlyArchive;
import io.github.cyonecoder.salatapi.archive.model.MonthlyArchiveManifest;
import io.github.cyonecoder.salatapi.archive.model.MonthlyArchiveStatus;
import io.github.cyonecoder.salatapi.archive.model.MonthlyCitySchedule;
import io.github.cyonecoder.salatapi.common.checksum.Sha256Service;
import io.github.cyonecoder.salatapi.config.SalatApiProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Persisting monthly archives and their manifests (spec 15 and 19).
 */
class JsonMonthlyArchiveRepositoryTest {

    @TempDir
    Path dataDir;

    private JsonMonthlyArchiveRepository repository;
    private SalatApiProperties properties;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        properties = new SalatApiProperties("Africa/Casablanca", dataDir);
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        repository = new JsonMonthlyArchiveRepository(
                properties, new AtomicJsonFileWriter(mapper), new Sha256Service());
    }

    private static ArchivedDay day() {
        return new ArchivedDay(LocalDate.of(2026, 7, 1),
                LocalTime.of(4, 28), LocalTime.of(6, 17), LocalTime.of(13, 36),
                LocalTime.of(17, 17), LocalTime.of(20, 47), LocalTime.of(22, 20),
                "abc", Instant.parse("2026-07-01T03:30:00Z"), Instant.parse("2026-07-15T03:30:00Z"));
    }

    private static GregorianMonthlyArchive archive(MonthlyArchiveStatus status,
            long complete, long partial, long failed, MonthlyCitySchedule... cities) {
        return new GregorianMonthlyArchive(
                GregorianMonthlyArchive.SCHEMA_VERSION, GregorianMonthlyArchive.SOURCE_OFFICIAL,
                "2026-07", GregorianMonthlyArchive.PERIOD_TYPE_GREGORIAN_MONTH,
                GregorianMonthlyArchive.TIMEZONE, status,
                Instant.parse("2026-07-15T03:30:00Z"), Instant.parse("2026-07-15T03:30:00Z"),
                191, cities.length, complete, partial, failed, List.of(cities));
    }

    private static MonthlyCitySchedule rabat(int daysAvailable, CityArchiveStatus status) {
        return new MonthlyCitySchedule("1", "الرباط", "rabat", true, status,
                31, daysAvailable, List.of(day()), null, List.of("abc"));
    }

    private static MonthlyCitySchedule failed() {
        return new MonthlyCitySchedule("4", "الرماني", null, false, CityArchiveStatus.UNAVAILABLE,
                31, 0, List.of(), ArchiveFailureCode.TIMETABLE_UNAVAILABLE, List.of());
    }

    @Test
    void savingWritesTheArchiveAndManifestInTheSpecifiedLayout() {
        repository.save(archive(MonthlyArchiveStatus.PARTIAL, 0, 1, 0, rabat(15, CityArchiveStatus.PARTIAL)),
                Instant.parse("2026-07-15T03:30:00Z"), List.of());

        Path dir = dataDir.resolve("archives/gregorian/2026/07");
        assertThat(dir.resolve("habous-prayer-times-2026-07.json")).isRegularFile();
        assertThat(dir.resolve("manifest.json")).isRegularFile();
    }

    @Test
    void theManifestSerializesExactlyAsDocumented() throws Exception {
        repository.save(
                archive(MonthlyArchiveStatus.PARTIAL, 0, 1, 0, rabat(15, CityArchiveStatus.PARTIAL)),
                Instant.parse("2026-07-15T03:30:00Z"), List.of());

        ObjectNode json = (ObjectNode) mapper.readTree(
                properties.archiveManifestFile(YearMonth.of(2026, 7)).toFile());

        assertThat(json.get("schemaVersion").asText()).isEqualTo("1.0");
        assertThat(json.get("period").asText()).isEqualTo("2026-07");
        assertThat(json.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(json.get("archiveFile").asText()).isEqualTo("habous-prayer-times-2026-07.json");
        assertThat(json.get("archiveSha256").asText()).hasSize(64);
        assertThat(json.get("officialCatalogCityCount").asInt()).isEqualTo(191);
        assertThat(json.get("archiveCityCount").asInt()).isEqualTo(1);
        assertThat(json.get("completeCityCount").asInt()).isZero();
        assertThat(json.get("partialCityCount").asInt()).isEqualTo(1);
        assertThat(json.get("unavailableCityCount").asInt()).isZero();
        assertThat(json.get("expectedDaysPerCompleteCity").asInt()).isEqualTo(31);
        assertThat(json.get("lastSuccessfulSyncAt").asText()).isEqualTo("2026-07-15T03:30:00Z");
        assertThat(json.get("parserVersion").asText()).isEqualTo("habous-live-1");
        assertThat(json.get("villeParserVersion").asText()).isEqualTo("habous-ville-select-1");
        assertThat(json.get("warnings")).isEmpty();
        assertThat(json.get("failedCities")).isEmpty();
    }

    @Test
    void theArchiveSha256MatchesTheArchiveFileByteForByte() throws Exception {
        MonthlyArchiveManifest manifest = repository.save(
                archive(MonthlyArchiveStatus.PARTIAL, 0, 1, 0, rabat(15, CityArchiveStatus.PARTIAL)),
                Instant.parse("2026-07-15T03:30:00Z"), List.of());

        String onDisk = Files.readString(properties.archiveFile(YearMonth.of(2026, 7)));
        assertThat(new Sha256Service().hex(onDisk)).isEqualTo(manifest.archiveSha256());
    }

    @Test
    void failedCitiesAreListedInTheManifestWithTheirCodes() {
        MonthlyArchiveManifest manifest = repository.save(
                archive(MonthlyArchiveStatus.PARTIAL, 0, 1, 1,
                        rabat(15, CityArchiveStatus.PARTIAL), failed()),
                Instant.parse("2026-07-15T03:30:00Z"), List.of("official catalogue changed during collection"));

        assertThat(manifest.unavailableCityCount()).isEqualTo(1);
        assertThat(manifest.failedCities()).singleElement().satisfies(city -> {
            assertThat(city.habousCityId()).isEqualTo("4");
            assertThat(city.officialNameAr()).isEqualTo("الرماني");
            assertThat(city.failureCode()).isEqualTo(ArchiveFailureCode.TIMETABLE_UNAVAILABLE);
        });
        assertThat(manifest.warnings()).containsExactly("official catalogue changed during collection");
    }

    @Test
    void rewritingIdenticalContentCreatesNoRevision() {
        var archive = archive(MonthlyArchiveStatus.PARTIAL, 0, 1, 0, rabat(15, CityArchiveStatus.PARTIAL));
        repository.save(archive, Instant.parse("2026-07-15T03:30:00Z"), List.of());
        repository.save(archive, Instant.parse("2026-07-15T03:30:00Z"), List.of());

        assertThat(repository.revisionChecksums(YearMonth.of(2026, 7))).isEmpty();
    }

    @Test
    void rewritingChangedContentRevisionsThePriorArchive() {
        repository.save(archive(MonthlyArchiveStatus.PARTIAL, 0, 1, 0, rabat(15, CityArchiveStatus.PARTIAL)),
                Instant.parse("2026-07-15T03:30:00Z"), List.of());

        // A later sync adds more days: the month grows, the prior state is kept.
        repository.save(archive(MonthlyArchiveStatus.PARTIAL, 0, 1, 0, rabat(20, CityArchiveStatus.PARTIAL)),
                Instant.parse("2026-07-16T03:30:00Z"), List.of());

        assertThat(repository.revisionChecksums(YearMonth.of(2026, 7))).hasSize(1);
        Path revisionDir = properties.archiveRevisionsDirectory(YearMonth.of(2026, 7))
                .resolve(repository.revisionChecksums(YearMonth.of(2026, 7)).get(0));
        assertThat(revisionDir.resolve("habous-prayer-times-2026-07.json")).isRegularFile();
        assertThat(revisionDir.resolve("manifest.json")).isRegularFile();

        // The current archive reflects the newer state.
        assertThat(repository.findArchive(YearMonth.of(2026, 7)).orElseThrow()
                .city("1").orElseThrow().daysAvailable()).isEqualTo(20);
    }

    @Test
    void archiveAndManifestRoundTrip() {
        repository.save(archive(MonthlyArchiveStatus.DEGRADED, 0, 1, 1,
                        rabat(15, CityArchiveStatus.PARTIAL), failed()),
                Instant.parse("2026-07-15T03:30:00Z"), List.of("a warning"));

        assertThat(repository.findManifest(YearMonth.of(2026, 7))).hasValueSatisfying(m -> {
            assertThat(m.status()).isEqualTo(MonthlyArchiveStatus.DEGRADED);
            assertThat(m.failedCities()).hasSize(1);
            assertThat(m.warnings()).containsExactly("a warning");
        });
        assertThat(repository.findArchive(YearMonth.of(2026, 7))).hasValueSatisfying(a ->
                assertThat(a.city("4").orElseThrow().failureCode())
                        .isEqualTo(ArchiveFailureCode.TIMETABLE_UNAVAILABLE));
    }

    @Test
    void anAbsentArchiveReadsAsEmpty() {
        assertThat(repository.findArchive(YearMonth.of(2026, 1))).isEmpty();
        assertThat(repository.findManifest(YearMonth.of(2026, 1))).isEmpty();
        assertThat(repository.revisionChecksums(YearMonth.of(2026, 1))).isEmpty();
    }
}
