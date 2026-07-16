package io.github.cyonecoder.salatapi.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.cyonecoder.salatapi.archive.io.AtomicJsonFileWriter;
import io.github.cyonecoder.salatapi.archive.model.MonthlyArchiveStatus;
import io.github.cyonecoder.salatapi.archive.repository.JsonMonthlyArchiveRepository;
import io.github.cyonecoder.salatapi.archive.repository.RawHabousSnapshotRepository;
import io.github.cyonecoder.salatapi.archive.service.GregorianArchiveAssembler;
import io.github.cyonecoder.salatapi.archive.service.GregorianArchiveAssembler.ObservedPage;
import io.github.cyonecoder.salatapi.archive.service.MonthlyArchiveService;
import io.github.cyonecoder.salatapi.city.ApplicationHabousMapping;
import io.github.cyonecoder.salatapi.city.CitySupportService;
import io.github.cyonecoder.salatapi.city.MoroccoCityRegistry;
import io.github.cyonecoder.salatapi.city.OfficialHabousCityDirectory;
import io.github.cyonecoder.salatapi.common.checksum.Sha256Service;
import io.github.cyonecoder.salatapi.config.SalatApiProperties;
import io.github.cyonecoder.salatapi.habous.model.HabousPageSnapshot;
import io.github.cyonecoder.salatapi.habous.model.HabousPublishedDay;
import io.github.cyonecoder.salatapi.habous.parser.HabousLiveHtmlParser;
import io.github.cyonecoder.salatapi.secapproach.configuration.SecApproachParameters;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The offline rebuild path used by the manual jobs (spec 25): reassemble archives
 * from stored raw snapshots, without any fetching.
 */
class RebuildJobTest {

    @TempDir
    Path dataDir;

    private RawHabousSnapshotRepository rawSnapshots;
    private JsonMonthlyArchiveRepository archiveRepo;
    private MonthlyArchiveService archives;
    private SalatApiProperties props;

    @BeforeEach
    void setUp() {
        props = new SalatApiProperties(dataDir);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        AtomicJsonFileWriter jsonFiles = new AtomicJsonFileWriter(mapper);
        Sha256Service sha = new Sha256Service();
        rawSnapshots = new RawHabousSnapshotRepository(props, sha, jsonFiles);
        CitySupportService support = new CitySupportService(
                new MoroccoCityRegistry(), new OfficialHabousCityDirectory(),
                new ApplicationHabousMapping(), new SecApproachParameters());
        Clock clock = Clock.fixed(Instant.parse("2026-07-16T03:00:00Z"), ZoneOffset.UTC);
        archiveRepo = new JsonMonthlyArchiveRepository(props, jsonFiles, sha);
        archives = new MonthlyArchiveService(new GregorianArchiveAssembler(support, clock), archiveRepo);
    }

    private void storeRabatSnapshot() {
        String html;
        try (InputStream in = getClass().getResourceAsStream(
                "/fixtures/habous/horaire-rabat-live-2026-muharram.html")) {
            html = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        List<HabousPublishedDay> days = new HabousLiveHtmlParser().parse(html);
        HabousPageSnapshot metadata = new HabousPageSnapshot(
                "1", "الرباط", "rabat", true, LocalDate.of(2026, 7, 16),
                Instant.parse("2026-07-16T03:00:00Z"), "https://example.test?ville=1",
                "recomputed", "habous-live-1", days.size(),
                days.get(0).date(), days.get(days.size() - 1).date(), List.of("2026-06", "2026-07"));
        rawSnapshots.save(html, days, metadata);
    }

    private List<ObservedPage> storedPages() {
        return rawSnapshots.listAllPrimary().stream()
                .map(s -> new ObservedPage(s.metadata(), s.days()))
                .toList();
    }

    @Test
    void storedSnapshotsAreEnumeratedForRebuild() {
        storeRabatSnapshot();

        List<RawHabousSnapshotRepository.StoredSnapshot> stored = rawSnapshots.listAllPrimary();

        assertThat(stored).singleElement().satisfies(s -> {
            assertThat(s.metadata().habousCityId()).isEqualTo("1");
            assertThat(s.days()).hasSize(30);
        });
    }

    @Test
    void revisionsAreExcludedFromTheRebuildInput() {
        storeRabatSnapshot();
        // A second, different source for the same city/day becomes a revision.
        storeRabatSnapshot();  // identical → duplicate, still one primary
        List<HabousPublishedDay> days = storedPages().get(0).days();
        rawSnapshots.save("<html>different</html>", days, storedPages().get(0).metadata());

        // Only the primary snapshot is enumerated, not the revision.
        assertThat(rawSnapshots.listAllPrimary()).hasSize(1);
    }

    @Test
    void rebuildReconstructsBothGregorianMonthsFromSnapshotsAlone() {
        storeRabatSnapshot();

        var manifests = archives.publish(
                storedPages(), new OfficialHabousCityDirectory().all().stream()
                        .map(v -> new io.github.cyonecoder.salatapi.habous.model.CityCatalogueEntry(
                                v.habousCityId(), v.habousArabicName(), v.habousArabicName(),
                                "bootstrap", Instant.EPOCH, Instant.EPOCH, true, List.of()))
                        .toList(),
                Map.of(), Instant.parse("2026-07-16T03:00:00Z"), false, List.of());

        assertThat(manifests).hasSize(2);   // June and July
        assertThat(archiveRepo.findArchive(YearMonth.of(2026, 7)).orElseThrow()
                .city("1").orElseThrow().daysAvailable()).isEqualTo(16);
    }

    @Test
    void rebuildRecoversACorruptExistingArchive() throws Exception {
        storeRabatSnapshot();
        List<io.github.cyonecoder.salatapi.habous.model.CityCatalogueEntry> cities =
                new OfficialHabousCityDirectory().all().stream()
                        .map(v -> new io.github.cyonecoder.salatapi.habous.model.CityCatalogueEntry(
                                v.habousCityId(), v.habousArabicName(), v.habousArabicName(),
                                "bootstrap", Instant.EPOCH, Instant.EPOCH, true, List.of()))
                        .toList();

        // Corrupt the July archive on disk, as if a crash left it half-written.
        Path julyFile = props.archiveFile(YearMonth.of(2026, 7));
        Files.createDirectories(julyFile.getParent());
        Files.writeString(julyFile, "{ this is not valid json");

        // Rebuild must recover it rather than choke — corruption is exactly the
        // reason someone runs rebuild-month.
        archives.publishMonth(YearMonth.of(2026, 7), storedPages(), cities, Map.of(),
                Instant.parse("2026-07-16T03:00:00Z"), false, List.of());

        assertThat(archiveRepo.findArchive(YearMonth.of(2026, 7))).isPresent();
        assertThat(archiveRepo.findArchive(YearMonth.of(2026, 7)).orElseThrow().status())
                .isEqualTo(MonthlyArchiveStatus.PARTIAL);
        // The corrupt version is preserved as a revision, never silently lost.
        assertThat(archiveRepo.revisionChecksums(YearMonth.of(2026, 7))).hasSize(1);
    }

    @Test
    void jobModeParsesEveryFlagAndRejectsUnknown() {
        assertThat(JobMode.parse("sync-now")).contains(JobMode.SYNC_NOW);
        assertThat(JobMode.parse("rebuild-archives")).contains(JobMode.REBUILD_ARCHIVES);
        assertThat(JobMode.parse("rebuild-month")).contains(JobMode.REBUILD_MONTH);
        assertThat(JobMode.parse("verify-date")).contains(JobMode.VERIFY_DATE);
        assertThat(JobMode.parse(null)).isEmpty();
        assertThat(JobMode.parse("  ")).isEmpty();
        assertThatThrownBy(() -> JobMode.parse("delete-everything"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
