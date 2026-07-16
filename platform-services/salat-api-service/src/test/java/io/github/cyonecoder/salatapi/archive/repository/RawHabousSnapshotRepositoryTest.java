package io.github.cyonecoder.salatapi.archive.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.cyonecoder.salatapi.archive.io.AtomicJsonFileWriter;
import io.github.cyonecoder.salatapi.archive.repository.RawHabousSnapshotRepository.SaveStatus;
import io.github.cyonecoder.salatapi.common.checksum.Sha256Service;
import io.github.cyonecoder.salatapi.config.SalatApiProperties;
import io.github.cyonecoder.salatapi.habous.model.HabousPageSnapshot;
import io.github.cyonecoder.salatapi.habous.model.HabousPublishedDay;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Raw official snapshot storage (spec 15).
 *
 * <p>The never-overwrite rule is the point of these tests. The official source
 * has no month parameter, so a page that gets overwritten is gone permanently —
 * there is no way to re-fetch it. These files are the only historical record that
 * will ever exist.
 */
class RawHabousSnapshotRepositoryTest {

    @TempDir
    Path dataDir;

    private RawHabousSnapshotRepository repository;
    private SalatApiProperties properties;

    private static final LocalDate FETCHED_ON = LocalDate.of(2026, 7, 15);
    private static final String VILLE = "117";

    @BeforeEach
    void setUp() {
        properties = new SalatApiProperties("Africa/Casablanca", dataDir);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        repository = new RawHabousSnapshotRepository(
                properties, new Sha256Service(), new AtomicJsonFileWriter(mapper));
    }

    private static List<HabousPublishedDay> days() {
        return List.of(new HabousPublishedDay(
                LocalDate.of(2026, 6, 17), LocalTime.of(4, 24), LocalTime.of(6, 13),
                LocalTime.of(13, 33), LocalTime.of(17, 14), LocalTime.of(20, 45), LocalTime.of(22, 19)));
    }

    private static HabousPageSnapshot metadata() {
        return new HabousPageSnapshot(
                VILLE, "أكادير", "agadir", true, FETCHED_ON,
                Instant.parse("2026-07-15T03:00:00Z"),
                "https://www.habous.gov.ma/prieres/horaire_hijri_2.php?ville=117",
                "ignored-recomputed", "habous-live-1", 30,
                LocalDate.of(2026, 6, 17), LocalDate.of(2026, 7, 16),
                List.of("2026-06", "2026-07"));
    }

    @Test
    void aFirstSaveCreatesThePrimarySnapshotInTheSpecifiedLayout() throws IOException {
        var result = repository.save("<html>v1</html>", days(), metadata());

        assertThat(result.status()).isEqualTo(SaveStatus.CREATED);
        assertThat(result.isStored()).isTrue();

        Path directory = dataDir.resolve("raw/habous/2026/07/15/117");
        assertThat(directory).isDirectory();
        assertThat(directory.resolve("source.html")).isRegularFile()
                .hasContent("<html>v1</html>");
        assertThat(directory.resolve("source.sha256")).isRegularFile();
        assertThat(directory.resolve("parsed-days.json")).isRegularFile();
        assertThat(directory.resolve("metadata.json")).isRegularFile();

        assertThat(Files.readString(directory.resolve("source.sha256")))
                .isEqualTo(result.checksum())
                .hasSize(64);
    }

    @Test
    void anIdenticalChecksumIsADuplicateAndNothingIsRewritten() throws IOException {
        repository.save("<html>same</html>", days(), metadata());
        Path html = dataDir.resolve("raw/habous/2026/07/15/117/source.html");
        var firstModified = Files.getLastModifiedTime(html);

        var result = repository.save("<html>same</html>", days(), metadata());

        assertThat(result.status()).isEqualTo(SaveStatus.DUPLICATE);
        assertThat(Files.getLastModifiedTime(html)).isEqualTo(firstModified);
        assertThat(repository.revisionChecksums(FETCHED_ON, VILLE)).isEmpty();
    }

    @Test
    void changedContentCreatesARevisionAndLeavesThePrimaryUntouched() {
        repository.save("<html>original</html>", days(), metadata());

        var result = repository.save("<html>republished</html>", days(), metadata());

        assertThat(result.status()).isEqualTo(SaveStatus.REVISION_SAVED);
        assertThat(result.isStored()).isTrue();

        // The only previous valid source must survive verbatim.
        assertThat(repository.findRawHtml(FETCHED_ON, VILLE)).contains("<html>original</html>");

        assertThat(result.location()).isDirectory();
        assertThat(result.location().resolve("source.html")).hasContent("<html>republished</html>");
        assertThat(result.location().getParent().getFileName().toString()).isEqualTo("revisions");
    }

    @Test
    void severalDistinctRevisionsAreAllKept() {
        repository.save("<html>v1</html>", days(), metadata());
        repository.save("<html>v2</html>", days(), metadata());
        repository.save("<html>v3</html>", days(), metadata());

        assertThat(repository.revisionChecksums(FETCHED_ON, VILLE)).hasSize(2);
        assertThat(repository.findRawHtml(FETCHED_ON, VILLE)).contains("<html>v1</html>");
    }

    @Test
    void aRepeatedRevisionIsNotDuplicatedAgain() {
        repository.save("<html>v1</html>", days(), metadata());
        repository.save("<html>v2</html>", days(), metadata());

        var result = repository.save("<html>v2</html>", days(), metadata());

        // v2 already exists as a revision; re-saving it must not multiply it.
        assertThat(result.status()).isEqualTo(SaveStatus.REVISION_SAVED);
        assertThat(repository.revisionChecksums(FETCHED_ON, VILLE)).hasSize(1);
    }

    @Test
    void snapshotsAreKeyedByFetchDayNotByThePeriodTheyCover() {
        repository.save("<html>monday</html>", days(), metadata());

        HabousPageSnapshot tuesday = new HabousPageSnapshot(
                VILLE, "أكادير", "agadir", true, LocalDate.of(2026, 7, 16),
                Instant.parse("2026-07-16T03:00:00Z"), "https://example.test", "x",
                "habous-live-1", 30, LocalDate.of(2026, 6, 17), LocalDate.of(2026, 7, 16),
                List.of("2026-06", "2026-07"));
        var result = repository.save("<html>tuesday</html>", days(), tuesday);

        // Same city, same published period, different fetch day: a new primary,
        // not a revision. The source only ever publishes "now", so the fetch date
        // is what identifies the observation.
        assertThat(result.status()).isEqualTo(SaveStatus.CREATED);
        assertThat(repository.findRawHtml(LocalDate.of(2026, 7, 16), VILLE))
                .contains("<html>tuesday</html>");
        assertThat(repository.findRawHtml(FETCHED_ON, VILLE)).contains("<html>monday</html>");
    }

    @Test
    void officialOnlyCitiesAreStoredUnderTheirVilleIdWithANullApplicationSlug() {
        // Ville 4 (الرماني) is one of the 131 with no application mapping. It is
        // archived exactly like any other city, keyed by its official id.
        HabousPageSnapshot officialOnly = new HabousPageSnapshot(
                "4", "الرماني", null, false, FETCHED_ON, Instant.parse("2026-07-15T03:00:00Z"),
                "https://example.test?ville=4", "x", "habous-live-1", 30,
                LocalDate.of(2026, 6, 17), LocalDate.of(2026, 7, 16), List.of("2026-06", "2026-07"));

        var result = repository.save("<html>rommani</html>", days(), officialOnly);

        assertThat(result.status()).isEqualTo(SaveStatus.CREATED);
        assertThat(dataDir.resolve("raw/habous/2026/07/15/4")).isDirectory();
        assertThat(repository.findMetadata(FETCHED_ON, "4")).hasValueSatisfying(saved -> {
            assertThat(saved.habousCityId()).isEqualTo("4");
            assertThat(saved.applicationCityId()).isNull();
            assertThat(saved.secApproachSupported()).isFalse();
            assertThat(saved.officialNameAr()).isEqualTo("الرماني");
        });
    }

    @Test
    void storedMetadataAndDaysRoundTrip() {
        repository.save("<html>x</html>", days(), metadata());

        assertThat(repository.findMetadata(FETCHED_ON, VILLE)).hasValueSatisfying(saved -> {
            assertThat(saved.gregorianMonths()).containsExactly("2026-06", "2026-07");
            assertThat(saved.firstDate()).isEqualTo(LocalDate.of(2026, 6, 17));
            assertThat(saved.lastDate()).isEqualTo(LocalDate.of(2026, 7, 16));
            assertThat(saved.parserVersion()).isEqualTo("habous-live-1");
        });
        assertThat(repository.findDays(FETCHED_ON, VILLE)).hasValueSatisfying(saved ->
                assertThat(saved).singleElement().satisfies(day -> {
                    assertThat(day.date()).isEqualTo(LocalDate.of(2026, 6, 17));
                    assertThat(day.fajr()).isEqualTo(LocalTime.of(4, 24));
                }));
    }

    @Test
    void anAbsentSnapshotReadsAsEmptyRatherThanFailing() {
        assertThat(repository.exists(FETCHED_ON, VILLE)).isFalse();
        assertThat(repository.findMetadata(FETCHED_ON, VILLE)).isEmpty();
        assertThat(repository.findDays(FETCHED_ON, VILLE)).isEmpty();
        assertThat(repository.findRawHtml(FETCHED_ON, VILLE)).isEmpty();
        assertThat(repository.revisionChecksums(FETCHED_ON, VILLE)).isEmpty();
    }

    @Test
    void aDirectoryWithoutItsChecksumIsTreatedAsAbsent() throws IOException {
        // A crash between writing the HTML and its digest must not leave a
        // half-snapshot that later reads as valid.
        Path directory = properties.rawSnapshotDirectory(FETCHED_ON, VILLE);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("source.html"), "<html>torn</html>");

        assertThat(repository.exists(FETCHED_ON, VILLE)).isFalse();

        var result = repository.save("<html>good</html>", days(), metadata());
        assertThat(result.status()).isEqualTo(SaveStatus.CREATED);
    }
}
