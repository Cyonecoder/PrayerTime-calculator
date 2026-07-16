package io.github.cyonecoder.salatapi.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.cyonecoder.salatapi.archive.io.AtomicJsonFileWriter;
import io.github.cyonecoder.salatapi.archive.model.MonthlyArchiveStatus;
import io.github.cyonecoder.salatapi.archive.repository.JsonMonthlyArchiveRepository;
import io.github.cyonecoder.salatapi.archive.repository.RawHabousSnapshotRepository;
import io.github.cyonecoder.salatapi.archive.repository.SyncReportRepository;
import io.github.cyonecoder.salatapi.archive.service.GregorianArchiveAssembler;
import io.github.cyonecoder.salatapi.archive.service.MonthlyArchiveService;
import io.github.cyonecoder.salatapi.city.ApplicationHabousMapping;
import io.github.cyonecoder.salatapi.city.CitySupportService;
import io.github.cyonecoder.salatapi.city.MoroccoCityRegistry;
import io.github.cyonecoder.salatapi.city.OfficialHabousCityDirectory;
import io.github.cyonecoder.salatapi.common.checksum.Sha256Service;
import io.github.cyonecoder.salatapi.config.HabousProperties;
import io.github.cyonecoder.salatapi.config.SalatApiProperties;
import io.github.cyonecoder.salatapi.habous.client.HabousClient;
import io.github.cyonecoder.salatapi.habous.client.HabousRequestFactory;
import io.github.cyonecoder.salatapi.habous.client.HabousRetryPolicy;
import io.github.cyonecoder.salatapi.habous.parser.HabousLiveHtmlParser;
import io.github.cyonecoder.salatapi.habous.parser.HabousVilleDirectoryParser;
import io.github.cyonecoder.salatapi.habous.service.HabousCityCatalogService;
import io.github.cyonecoder.salatapi.habous.service.HabousCollectionService;
import io.github.cyonecoder.salatapi.habous.validation.HabousTimetableValidator;
import io.github.cyonecoder.salatapi.secapproach.calculation.HabousRawEngine;
import io.github.cyonecoder.salatapi.secapproach.configuration.ReconstructedMethod;
import io.github.cyonecoder.salatapi.secapproach.configuration.SecApproachParameters;
import io.github.cyonecoder.salatapi.secapproach.service.SecApproachCalculator;
import io.github.cyonecoder.salatapi.verification.DailyVerificationService;
import io.github.cyonecoder.salatapi.verification.PrayerTimeComparisonService;
import io.github.cyonecoder.salatapi.verification.VerificationReportRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Objects;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole daily workflow end to end (spec 20), driven against a MockWebServer
 * serving the real captured Rabat page. Verifies the pipeline runs, publishes
 * both Gregorian months the Hijri page spans, and records a report — and that a
 * failing city does not stop the run.
 */
class HabousDailySyncJobTest {

    @TempDir
    Path dataDir;

    private MockWebServer server;
    private HabousDailySyncJob job;
    private SalatApiProperties salatProps;
    private JsonMonthlyArchiveRepository archiveRepo;
    private SyncReportRepository syncReports;

    private static String realTimetable() {
        try (InputStream in = HabousDailySyncJobTest.class.getResourceAsStream(
                "/fixtures/habous/horaire-rabat-live-2026-muharram.html")) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        // Every ville request returns the Rabat page. Rabat (ville 1) validates;
        // any other ville is rejected as WRONG_CITY — exactly the isolation we want
        // to exercise across a 191-city catalogue without 191 distinct fixtures.
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setBody(realTimetable());
            }
        });
        server.start();

        HabousProperties habousProps = new HabousProperties(
                server.url("/horaire").toString(), server.url("/index").toString(), "agent",
                1, Duration.ZERO, Duration.ofSeconds(5), Duration.ofSeconds(5),
                1, Duration.ofSeconds(1), Duration.ofSeconds(30));
        salatProps = new SalatApiProperties(dataDir);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        AtomicJsonFileWriter jsonFiles = new AtomicJsonFileWriter(mapper);
        Sha256Service sha = new Sha256Service();
        Clock clock = Clock.fixed(Instant.parse("2026-07-16T02:15:00Z"), ZoneOffset.UTC);

        var registry = new MoroccoCityRegistry();
        var directory = new OfficialHabousCityDirectory();
        var mapping = new ApplicationHabousMapping();
        var params = new SecApproachParameters();
        var support = new CitySupportService(registry, directory, mapping, params);

        var villeParser = new HabousVilleDirectoryParser(sha);
        var catalog = new HabousCityCatalogService(directory, villeParser, jsonFiles, salatProps, clock);
        var client = new HabousClient(
                java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                new HabousRequestFactory(habousProps), new HabousRetryPolicy(habousProps),
                d -> { }, habousProps);
        var rawSnapshots = new RawHabousSnapshotRepository(salatProps, sha, jsonFiles);
        var collection = new HabousCollectionService(
                client, new HabousLiveHtmlParser(), villeParser, new HabousTimetableValidator(),
                rawSnapshots, support, habousProps, clock);
        var assembler = new GregorianArchiveAssembler(support, clock);
        archiveRepo = new JsonMonthlyArchiveRepository(salatProps, jsonFiles, sha);
        var archives = new MonthlyArchiveService(assembler, archiveRepo);
        var secApproach = new SecApproachCalculator(
                registry, new HabousRawEngine(), ReconstructedMethod.frozen(), params);
        var verification = new DailyVerificationService(
                archives, secApproach, new PrayerTimeComparisonService(),
                new VerificationReportRepository(salatProps, jsonFiles), clock);
        syncReports = new SyncReportRepository(salatProps, jsonFiles);

        job = new HabousDailySyncJob(
                new SyncExecutionLock(salatProps), catalog, collection, archives, verification,
                syncReports, client, clock);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void aFullRunPublishesBothGregorianMonthsTheHijriPageSpans() {
        SyncJobReport report = job.runOnce();

        // The catalogue lists 191 villes; only Rabat's page validates, so the run
        // is a partial success — but it completes rather than aborting.
        assertThat(report.outcome()).isEqualTo(SyncJobReport.Outcome.PARTIAL_SUCCESS);
        assertThat(report.officialCityCount()).isEqualTo(191);
        assertThat(report.collectedCityCount()).isEqualTo(1);        // only Rabat
        assertThat(report.failedCityCount()).isEqualTo(190);          // the rest are WRONG_CITY

        // One Hijri page spans June and July: both are published.
        assertThat(report.publishedMonths()).containsExactly("2026-06", "2026-07");
        assertThat(archiveRepo.findArchive(YearMonth.of(2026, 6))).isPresent();
        assertThat(archiveRepo.findArchive(YearMonth.of(2026, 7))).isPresent();
    }

    @Test
    void everyOfficialCityIsListedInTheArchiveIncludingTheFailedOnes() {
        job.runOnce();

        var july = archiveRepo.findArchive(YearMonth.of(2026, 7)).orElseThrow();
        // 191 official cities, all present; Rabat has data, the other 190 are unavailable.
        assertThat(july.cityCount()).isEqualTo(191);
        assertThat(july.city("1").orElseThrow().daysAvailable()).isEqualTo(16);
        assertThat(july.failedCityCount()).isEqualTo(190);
        assertThat(july.status()).isEqualTo(MonthlyArchiveStatus.DEGRADED); // WRONG_CITY is permanent
    }

    @Test
    void theRunProducesArchiveManifestVerificationAndSyncReports() {
        SyncJobReport report = job.runOnce();

        assertThat(archiveRepo.findManifest(YearMonth.of(2026, 7))).isPresent();
        assertThat(report.verificationRun()).isTrue();
        assertThat(syncReports.find(report.runDate())).isPresent();
        // The verification report for a covered date exists.
        Path verifyDir = salatProps.verificationDailyDirectory();
        assertThat(verifyDir.resolve("verify-2026-07-01.json")).isRegularFile();
    }

    @Test
    void rabatVerifiesExactlyAgainstItsOwnOfficialData() {
        job.runOnce();

        // Rabat is a sec-approach city and its official data is Rabat's page, so
        // the intersection is non-empty and the sec-approach engine is exercised
        // against real official times.
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var repo = new VerificationReportRepository(salatProps, new AtomicJsonFileWriter(mapper));
        assertThat(repo.find(java.time.LocalDate.of(2026, 7, 1))).hasValueSatisfying(r -> {
            assertThat(r.parameterVersion()).isEqualTo("morocco-habous-secaproach-v2");
            assertThat(r.comparedCityCount()).isGreaterThanOrEqualTo(1);
        });
    }

    @Test
    void aRunIsSkippedWhileAnotherHoldsTheLock() {
        // A second lock object shares the same on-disk lock file. While it is held,
        // the job's own run must not proceed — concurrent writers would corrupt the
        // archive files, and there is no database transaction to fall back on.
        SyncExecutionLock external = new SyncExecutionLock(salatProps);
        var handle = external.tryAcquire();
        assertThat(handle).isPresent();
        try {
            SyncJobReport report = job.runOnce();
            assertThat(report.outcome()).isEqualTo(SyncJobReport.Outcome.SKIPPED);
            assertThat(report.warnings()).anyMatch(w -> w.contains("already in progress"));
        } finally {
            handle.get().close();
        }

        // Once released, the job runs normally.
        assertThat(job.runOnce().outcome()).isEqualTo(SyncJobReport.Outcome.PARTIAL_SUCCESS);
    }
}
