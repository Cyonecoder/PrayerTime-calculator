package io.github.cyonecoder.salatapi.habous.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.cyonecoder.salatapi.archive.io.AtomicJsonFileWriter;
import io.github.cyonecoder.salatapi.archive.model.ArchiveFailureCode;
import io.github.cyonecoder.salatapi.archive.repository.RawHabousSnapshotRepository;
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
import io.github.cyonecoder.salatapi.habous.model.CityCatalogueEntry;
import io.github.cyonecoder.salatapi.habous.model.HabousCitySyncResult.Status;
import io.github.cyonecoder.salatapi.habous.parser.HabousLiveHtmlParser;
import io.github.cyonecoder.salatapi.habous.parser.HabousVilleDirectoryParser;
import io.github.cyonecoder.salatapi.habous.validation.HabousTimetableValidator;
import io.github.cyonecoder.salatapi.secapproach.configuration.SecApproachParameters;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Per-city collection and, above all, failure isolation (spec 20).
 *
 * <p>The rule "a failure for one official city must not erase valid data for
 * other cities" is the property these tests exist to protect.
 */
class HabousCollectionServiceTest {

    @TempDir
    Path dataDir;

    private MockWebServer server;
    private HabousCollectionService service;
    private RawHabousSnapshotRepository rawSnapshots;

    private static String realTimetable() {
        try (InputStream in = HabousCollectionServiceTest.class.getResourceAsStream(
                "/fixtures/habous/horaire-rabat-live-2026-muharram.html")) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static CityCatalogueEntry entry(String id, String name) {
        Instant now = Instant.parse("2026-07-16T03:00:00Z");
        return new CityCatalogueEntry(id, name, name, "checksum", now, now, true, List.of());
    }

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        HabousProperties properties = HabousProperties.forTests(
                server.url("/horaire").toString(), Duration.ZERO,
                Duration.ofSeconds(5), Duration.ofSeconds(5), 1, "agent");
        SalatApiProperties salatProps = new SalatApiProperties("Africa/Casablanca", dataDir);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        AtomicJsonFileWriter jsonFiles = new AtomicJsonFileWriter(mapper);
        Sha256Service sha = new Sha256Service();
        rawSnapshots = new RawHabousSnapshotRepository(salatProps, sha, jsonFiles);

        HabousClient client = new HabousClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                new HabousRequestFactory(properties), new HabousRetryPolicy(properties),
                d -> { }, properties);
        CitySupportService support = new CitySupportService(
                new MoroccoCityRegistry(), new OfficialHabousCityDirectory(),
                new ApplicationHabousMapping(), new SecApproachParameters());

        service = new HabousCollectionService(
                client, new HabousLiveHtmlParser(), new HabousVilleDirectoryParser(sha),
                new HabousTimetableValidator(), rawSnapshots, support, properties,
                Clock.fixed(Instant.parse("2026-07-16T03:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void aSuccessfullyCollectedCityIsStoredWithItsParsedDays() {
        server.enqueue(new MockResponse().setBody(realTimetable()));

        var outcome = service.collectAll(List.of(entry("1", "الرباط")));

        assertThat(outcome.results()).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(Status.COLLECTED);
            assertThat(r.isStored()).isTrue();
        });
        assertThat(outcome.pages()).singleElement().satisfies(page ->
                assertThat(page.days()).hasSize(30));
        assertThat(rawSnapshots.exists(LocalDate.of(2026, 7, 16), "1")).isTrue();
    }

    @Test
    void aFetchFailureForOneCityDoesNotEraseAnotherCitysData() {
        // City 1 succeeds; city 999 times out. Order interleaves them.
        server.enqueue(new MockResponse().setBody(realTimetable()));          // ville 1
        server.enqueue(new MockResponse().setResponseCode(503));              // ville 999
        server.enqueue(new MockResponse().setBody(realTimetable()));          // ville 1 again (2nd sync city)

        var outcome = service.collectAll(List.of(
                entry("1", "الرباط"), entry("999", "مدينة الاختبار"), entry("1", "الرباط")));

        assertThat(outcome.results()).hasSize(3);
        assertThat(outcome.results().get(0).status()).isEqualTo(Status.COLLECTED);
        assertThat(outcome.results().get(1).status()).isEqualTo(Status.FETCH_FAILED);
        assertThat(outcome.results().get(1).failureCode())
                .isEqualTo(ArchiveFailureCode.TIMETABLE_UNAVAILABLE);
        // The failure between two good cities did not stop the run or drop city 1.
        assertThat(rawSnapshots.exists(LocalDate.of(2026, 7, 16), "1")).isTrue();
        assertThat(outcome.pages()).hasSize(2);
    }

    @Test
    void everyCityIsAttemptedEvenWhenTheFirstFails() {
        // Both entries are ville 1 (the only city the fixture page is for); the
        // point is that a first-city failure does not abort the whole run.
        server.enqueue(new MockResponse().setResponseCode(503));       // first attempt fails
        server.enqueue(new MockResponse().setBody(realTimetable()));   // second attempt succeeds

        var outcome = service.collectAll(List.of(entry("1", "الرباط"), entry("1", "الرباط")));

        assertThat(outcome.results()).extracting(r -> r.status())
                .containsExactly(Status.FETCH_FAILED, Status.COLLECTED);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void aWrongCityResponseIsRejectedRatherThanStoredUnderTheRequestedId() {
        // We ask for ville 2 but the site returns Rabat's page (selected = ville 1).
        server.enqueue(new MockResponse().setBody(realTimetable()));

        var outcome = service.collectAll(List.of(entry("2", "الدار البيضاء")));

        assertThat(outcome.results()).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(Status.WRONG_CITY);
            assertThat(r.failureCode()).isEqualTo(ArchiveFailureCode.PAGE_UNPARSEABLE);
        });
        // Nothing stored under ville 2.
        assertThat(rawSnapshots.exists(LocalDate.of(2026, 7, 16), "2")).isFalse();
        assertThat(outcome.pages()).isEmpty();
    }

    @Test
    void anUnparseablePageIsAnInvalidPageFailure() {
        server.enqueue(new MockResponse().setBody("<html><body>الموقع في صيانة</body></html>"));

        var outcome = service.collectAll(List.of(entry("1", "الرباط")));

        assertThat(outcome.results()).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(Status.INVALID_PAGE);
            assertThat(r.failureCode()).isEqualTo(ArchiveFailureCode.PAGE_UNPARSEABLE);
        });
        assertThat(outcome.pages()).isEmpty();
    }

    @Test
    void reCollectingIdenticalContentReportsUnchanged() {
        server.enqueue(new MockResponse().setBody(realTimetable()));
        server.enqueue(new MockResponse().setBody(realTimetable()));

        service.collectAll(List.of(entry("1", "الرباط")));
        var second = service.collectAll(List.of(entry("1", "الرباط")));

        assertThat(second.results().get(0).status()).isEqualTo(Status.UNCHANGED);
    }
}
