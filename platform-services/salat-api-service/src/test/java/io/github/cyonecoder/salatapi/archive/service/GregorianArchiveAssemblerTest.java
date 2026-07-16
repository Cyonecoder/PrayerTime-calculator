package io.github.cyonecoder.salatapi.archive.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.salatapi.archive.model.ArchiveFailureCode;
import io.github.cyonecoder.salatapi.archive.model.ArchivedDay;
import io.github.cyonecoder.salatapi.archive.model.CityArchiveStatus;
import io.github.cyonecoder.salatapi.archive.model.GregorianMonthlyArchive;
import io.github.cyonecoder.salatapi.archive.model.MonthlyArchiveStatus;
import io.github.cyonecoder.salatapi.archive.service.GregorianArchiveAssembler.ObservedPage;
import io.github.cyonecoder.salatapi.city.ApplicationHabousMapping;
import io.github.cyonecoder.salatapi.city.CitySupportService;
import io.github.cyonecoder.salatapi.city.MoroccoCityRegistry;
import io.github.cyonecoder.salatapi.city.OfficialHabousCityDirectory;
import io.github.cyonecoder.salatapi.habous.model.CityCatalogueEntry;
import io.github.cyonecoder.salatapi.habous.model.HabousPageSnapshot;
import io.github.cyonecoder.salatapi.habous.model.HabousPublishedDay;
import io.github.cyonecoder.salatapi.habous.parser.HabousLiveHtmlParser;
import io.github.cyonecoder.salatapi.secapproach.configuration.SecApproachParameters;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Gregorian monthly archive assembly and its JSON shape (spec 16 and 17).
 *
 * <p>The central rule: a row belongs to the Gregorian month of its own resolved
 * date, never to the month the page was fetched in. The primary case is driven by
 * the real captured Habous page, which spans 2026-06-17 to 2026-07-16.
 */
class GregorianArchiveAssemblerTest {

    private static final String FIXTURE = "/fixtures/habous/horaire-rabat-live-2026-muharram.html";
    private static final Instant FETCH = Instant.parse("2026-07-01T03:00:00Z");

    private static GregorianArchiveAssembler assembler;

    @BeforeAll
    static void setUp() {
        CitySupportService support = new CitySupportService(
                new MoroccoCityRegistry(), new OfficialHabousCityDirectory(),
                new ApplicationHabousMapping(), new SecApproachParameters());
        assembler = new GregorianArchiveAssembler(
                support, Clock.fixed(Instant.parse("2026-07-20T02:00:00Z"), ZoneOffset.UTC));
    }

    private static String realPage() {
        try (InputStream in = GregorianArchiveAssemblerTest.class.getResourceAsStream(FIXTURE)) {
            return new String(Objects.requireNonNull(in, "missing " + FIXTURE).readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Ville 1 = الرباط = rabat (mapped, sec-approach). */
    private static CityCatalogueEntry rabatEntry() {
        return entry("1", "الرباط");
    }

    /** Ville 4 = الرماني = official-only (no application mapping, no offsets). */
    private static CityCatalogueEntry rommaniEntry() {
        return entry("4", "الرماني");
    }

    private static CityCatalogueEntry entry(String id, String name) {
        return new CityCatalogueEntry(id, name, name, "checksum", FETCH, FETCH, true, List.of());
    }

    private static HabousPageSnapshot metadata(String cityId, Instant fetchedAt) {
        return new HabousPageSnapshot(
                cityId, "الرباط", "rabat", true, LocalDate.ofInstant(fetchedAt, ZoneOffset.UTC),
                fetchedAt, "https://example.test?ville=" + cityId,
                "checksum-" + fetchedAt.getEpochSecond(), "habous-live-1", 30,
                LocalDate.of(2026, 6, 17), LocalDate.of(2026, 7, 16), List.of("2026-06", "2026-07"));
    }

    private static HabousPublishedDay day(LocalDate date, int fajrHour, int fajrMinute) {
        return new HabousPublishedDay(date, LocalTime.of(fajrHour, fajrMinute), LocalTime.of(6, 13),
                LocalTime.of(13, 33), LocalTime.of(17, 14), LocalTime.of(20, 45), LocalTime.of(22, 19));
    }

    private static ObservedPage realObservedPage(Instant fetchedAt) {
        return new ObservedPage(metadata("1", fetchedAt), new HabousLiveHtmlParser().parse(realPage()));
    }

    private GregorianMonthlyArchive assemble(YearMonth month, List<ObservedPage> pages) {
        return assembler.assemble(month, pages, List.of(rabatEntry()), Map.of(), null);
    }

    // --- spec 16: the split ---------------------------------------------------

    @Test
    void oneHijriPageIsSplitAcrossTwoGregorianArchives() {
        ObservedPage page = realObservedPage(FETCH);

        assertThat(assembler.monthsCovered(List.of(page)))
                .containsExactly(YearMonth.of(2026, 6), YearMonth.of(2026, 7));
        assertThat(assemble(YearMonth.of(2026, 6), List.of(page)).city("1").orElseThrow()
                .daysAvailable()).isEqualTo(14);   // 17..30 June
        assertThat(assemble(YearMonth.of(2026, 7), List.of(page)).city("1").orElseThrow()
                .daysAvailable()).isEqualTo(16);   // 1..16 July
    }

    @Test
    void rowsAreNeverAssignedToTheMonthThePageWasFetchedIn() {
        // Fetched on 1 July, but fourteen of its rows are June days. Filing the
        // whole page under July would misdate them by ~4 weeks.
        ObservedPage page = realObservedPage(FETCH);

        assertThat(assemble(YearMonth.of(2026, 7), List.of(page)).city("1").orElseThrow().days())
                .allSatisfy(d -> assertThat(YearMonth.from(d.date())).isEqualTo(YearMonth.of(2026, 7)));
        assertThat(assemble(YearMonth.of(2026, 6), List.of(page)).city("1").orElseThrow().days())
                .allSatisfy(d -> assertThat(d.date().getMonthValue()).isEqualTo(6));
    }

    @Test
    void nothingIsLostInTheSplit() {
        ObservedPage page = realObservedPage(FETCH);

        int june = assemble(YearMonth.of(2026, 6), List.of(page)).city("1").orElseThrow().daysAvailable();
        int july = assemble(YearMonth.of(2026, 7), List.of(page)).city("1").orElseThrow().daysAvailable();

        assertThat(june + july).isEqualTo(30);
    }

    @Test
    void deduplicationIsByCityIdAndGregorianDate() {
        ObservedPage monday = realObservedPage(FETCH);
        ObservedPage tuesday = realObservedPage(Instant.parse("2026-07-02T03:00:00Z"));

        var july = assemble(YearMonth.of(2026, 7), List.of(monday, tuesday));

        assertThat(july.city("1").orElseThrow().daysAvailable()).isEqualTo(16);
        assertThat(july.city("1").orElseThrow().days()).extracting(ArchivedDay::date)
                .doesNotHaveDuplicates();
    }

    @Test
    void whenTheSameDateIsRepublishedTheNewerObservationWins() {
        ObservedPage older = new ObservedPage(metadata("1", FETCH),
                List.of(day(LocalDate.of(2026, 7, 5), 4, 30)));
        ObservedPage newer = new ObservedPage(metadata("1", Instant.parse("2026-07-10T03:00:00Z")),
                List.of(day(LocalDate.of(2026, 7, 5), 4, 33)));

        // Input order must not matter; the fetch instant decides.
        assertThat(assemble(YearMonth.of(2026, 7), List.of(older, newer))
                .city("1").orElseThrow().days().get(0).fajr()).isEqualTo(LocalTime.of(4, 33));
        assertThat(assemble(YearMonth.of(2026, 7), List.of(newer, older))
                .city("1").orElseThrow().days().get(0).fajr()).isEqualTo(LocalTime.of(4, 33));
    }

    @Test
    void theObservationWindowBracketsEverySighting() {
        ObservedPage first = new ObservedPage(metadata("1", FETCH),
                List.of(day(LocalDate.of(2026, 7, 5), 4, 30)));
        ObservedPage later = new ObservedPage(metadata("1", Instant.parse("2026-07-10T03:00:00Z")),
                List.of(day(LocalDate.of(2026, 7, 5), 4, 30)));

        ArchivedDay archived = assemble(YearMonth.of(2026, 7), List.of(first, later))
                .city("1").orElseThrow().days().get(0);

        // A Hijri page stays published for weeks, so the same date is re-observed
        // daily. Both ends of that window are recorded.
        assertThat(archived.firstObservedAt()).isEqualTo(FETCH);
        assertThat(archived.lastObservedAt()).isEqualTo(Instant.parse("2026-07-10T03:00:00Z"));
        assertThat(archived.sourceChecksum()).isNotBlank();
    }

    @Test
    void twoConsecutiveHijriPagesCompleteAGregorianMonth() {
        ObservedPage first = realObservedPage(FETCH);
        List<HabousPublishedDay> nextHijri = new java.util.ArrayList<>();
        for (int d = 17; d <= 31; d++) {
            nextHijri.add(day(LocalDate.of(2026, 7, d), 4, 45));
        }
        ObservedPage second = new ObservedPage(
                metadata("1", Instant.parse("2026-07-17T03:00:00Z")), nextHijri);

        var july = assemble(YearMonth.of(2026, 7), List.of(first, second));

        assertThat(july.city("1").orElseThrow().daysAvailable()).isEqualTo(31);
        assertThat(july.city("1").orElseThrow().status()).isEqualTo(CityArchiveStatus.COMPLETE);
        assertThat(july.status()).isEqualTo(MonthlyArchiveStatus.COMPLETE);
        assertThat(july.city("1").orElseThrow().sourceChecksums()).hasSize(2);
    }

    // --- spec 17: the schema --------------------------------------------------

    @Test
    void theArchiveCarriesTheDocumentedSchemaEnvelope() {
        var july = assemble(YearMonth.of(2026, 7), List.of(realObservedPage(FETCH)));

        assertThat(july.schemaVersion()).isEqualTo("1.0");
        assertThat(july.source()).isEqualTo("HABOUS_OFFICIAL");
        assertThat(july.period()).isEqualTo("2026-07");
        assertThat(july.periodType()).isEqualTo("GREGORIAN_MONTH");
        assertThat(july.timezone()).isEqualTo("Africa/Casablanca");
        assertThat(july.status()).isEqualTo(MonthlyArchiveStatus.PARTIAL);
        assertThat(july.yearMonth()).isEqualTo(YearMonth.of(2026, 7));
    }

    @Test
    void aCityEntryCarriesItsIdentityStatusAndDayCounts() {
        var july = assemble(YearMonth.of(2026, 7), List.of(realObservedPage(FETCH)));

        assertThat(july.city("1")).hasValueSatisfying(rabat -> {
            assertThat(rabat.habousCityId()).isEqualTo("1");
            assertThat(rabat.officialNameAr()).isEqualTo("الرباط");
            assertThat(rabat.applicationCityId()).isEqualTo("rabat");
            assertThat(rabat.secApproachSupported()).isTrue();
            assertThat(rabat.status()).isEqualTo(CityArchiveStatus.PARTIAL);
            assertThat(rabat.daysExpected()).isEqualTo(31);
            assertThat(rabat.daysAvailable()).isEqualTo(16);
            assertThat(rabat.failureCode()).isNull();
        });
    }

    @Test
    void everyOfficialCatalogueCityAppearsEvenWhenCollectionFailed() {
        // Only Rabat was collected; Rommani failed. Both must be listed.
        var july = assembler.assemble(
                YearMonth.of(2026, 7), List.of(realObservedPage(FETCH)),
                List.of(rabatEntry(), rommaniEntry()),
                Map.of("4", ArchiveFailureCode.TIMETABLE_UNAVAILABLE), null);

        assertThat(july.cities()).extracting(c -> c.habousCityId()).containsExactly("1", "4");
        assertThat(july.city("4")).hasValueSatisfying(failed -> {
            assertThat(failed.officialNameAr()).isEqualTo("الرماني");
            assertThat(failed.status()).isEqualTo(CityArchiveStatus.UNAVAILABLE);
            assertThat(failed.daysExpected()).isEqualTo(31);
            assertThat(failed.daysAvailable()).isZero();
            assertThat(failed.failureCode()).isEqualTo(ArchiveFailureCode.TIMETABLE_UNAVAILABLE);
            assertThat(failed.days()).isEmpty();
        });
    }

    @Test
    void anOfficialOnlyCityIsListedWithANullSlugAndNoSecApproach() {
        var july = assembler.assemble(
                YearMonth.of(2026, 7), List.of(), List.of(rommaniEntry()),
                Map.of("4", ArchiveFailureCode.TIMETABLE_UNAVAILABLE), null);

        // Collecting official data for a city never makes it a sec-approach city.
        assertThat(july.city("4")).hasValueSatisfying(city -> {
            assertThat(city.applicationCityId()).isNull();
            assertThat(city.secApproachSupported()).isFalse();
        });
        assertThat(july.secApproachCities()).isEmpty();
    }

    @Test
    void anUncollectedCityIsNotYetCollectedRatherThanUnexplained() {
        var july = assembler.assemble(
                YearMonth.of(2026, 7), List.of(), List.of(rabatEntry()), Map.of(), null);

        assertThat(july.city("1").orElseThrow().failureCode())
                .isEqualTo(ArchiveFailureCode.NOT_YET_COLLECTED);
    }

    @Test
    void theCountsAreRealAndSumToTheCityCount() {
        var july = assembler.assemble(
                YearMonth.of(2026, 7), List.of(realObservedPage(FETCH)),
                List.of(rabatEntry(), rommaniEntry()),
                Map.of("4", ArchiveFailureCode.TIMETABLE_UNAVAILABLE), null);

        assertThat(july.officialCatalogCityCount()).isEqualTo(2);
        assertThat(july.cityCount()).isEqualTo(2);
        assertThat(july.completeCityCount()).isZero();
        assertThat(july.partialCityCount()).isEqualTo(1);
        assertThat(july.failedCityCount()).isEqualTo(1);
        assertThat(july.completeCityCount() + july.partialCityCount() + july.failedCityCount())
                .isEqualTo(july.cityCount());
    }

    @Test
    void generatedAtIsPreservedAcrossReassemblyWhileLastUpdatedAtMoves() {
        Instant firstAssembly = Instant.parse("2026-07-02T03:00:00Z");

        var july = assembler.assemble(
                YearMonth.of(2026, 7), List.of(realObservedPage(FETCH)),
                List.of(rabatEntry()), Map.of(), firstAssembly);

        assertThat(july.generatedAt()).isEqualTo(firstAssembly);
        assertThat(july.lastUpdatedAt()).isEqualTo(Instant.parse("2026-07-20T02:00:00Z"));
    }

    @Test
    void aMonthWithNoContributingRowsListsEveryCityAsUnavailable() {
        var december = assembler.assemble(
                YearMonth.of(2026, 12), List.of(realObservedPage(FETCH)),
                List.of(rabatEntry()), Map.of(), null);

        assertThat(december.status()).isEqualTo(MonthlyArchiveStatus.PARTIAL);
        assertThat(december.failedCityCount()).isEqualTo(1);
        assertThat(december.city("1").orElseThrow().status()).isEqualTo(CityArchiveStatus.UNAVAILABLE);
    }

    @Test
    void aYearCrossingPageSplitsAcrossTheYearBoundary() {
        ObservedPage page = new ObservedPage(
                metadata("1", Instant.parse("2027-01-02T03:00:00Z")),
                List.of(day(LocalDate.of(2026, 12, 30), 6, 30),
                        day(LocalDate.of(2026, 12, 31), 6, 31),
                        day(LocalDate.of(2027, 1, 1), 6, 32)));

        assertThat(assembler.monthsCovered(List.of(page)))
                .containsExactly(YearMonth.of(2026, 12), YearMonth.of(2027, 1));
        assertThat(assemble(YearMonth.of(2026, 12), List.of(page))
                .city("1").orElseThrow().daysAvailable()).isEqualTo(2);
        assertThat(assemble(YearMonth.of(2027, 1), List.of(page))
                .city("1").orElseThrow().daysAvailable()).isEqualTo(1);
    }
}
