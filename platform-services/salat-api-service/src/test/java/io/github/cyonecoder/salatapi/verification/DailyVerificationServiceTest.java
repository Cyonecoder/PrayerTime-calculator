package io.github.cyonecoder.salatapi.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.cyonecoder.salatapi.archive.io.AtomicJsonFileWriter;
import io.github.cyonecoder.salatapi.archive.model.ArchivedDay;
import io.github.cyonecoder.salatapi.archive.model.CityArchiveStatus;
import io.github.cyonecoder.salatapi.archive.model.GregorianMonthlyArchive;
import io.github.cyonecoder.salatapi.archive.model.MonthlyArchiveStatus;
import io.github.cyonecoder.salatapi.archive.model.MonthlyCitySchedule;
import io.github.cyonecoder.salatapi.archive.repository.JsonMonthlyArchiveRepository;
import io.github.cyonecoder.salatapi.archive.service.GregorianArchiveAssembler;
import io.github.cyonecoder.salatapi.archive.service.MonthlyArchiveService;
import io.github.cyonecoder.salatapi.city.ApplicationHabousMapping;
import io.github.cyonecoder.salatapi.city.CitySupportService;
import io.github.cyonecoder.salatapi.city.MoroccoCityRegistry;
import io.github.cyonecoder.salatapi.city.OfficialHabousCityDirectory;
import io.github.cyonecoder.salatapi.common.checksum.Sha256Service;
import io.github.cyonecoder.salatapi.common.model.Prayer;
import io.github.cyonecoder.salatapi.common.model.PrayerTimes;
import io.github.cyonecoder.salatapi.config.SalatApiProperties;
import io.github.cyonecoder.salatapi.secapproach.calculation.HabousRawEngine;
import io.github.cyonecoder.salatapi.secapproach.configuration.ReconstructedMethod;
import io.github.cyonecoder.salatapi.secapproach.configuration.SecApproachParameters;
import io.github.cyonecoder.salatapi.secapproach.service.SecApproachCalculator;
import io.github.cyonecoder.salatapi.verification.model.DailyVerificationReport;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The verification intersection (spec 21): compare a city only when an official
 * row exists AND an application mapping exists AND sec-approach offsets exist.
 */
class DailyVerificationServiceTest {

    @TempDir
    Path dataDir;

    private static final LocalDate DATE = LocalDate.of(2026, 7, 1);

    private DailyVerificationService verification;
    private JsonMonthlyArchiveRepository archiveRepo;
    private SecApproachCalculator secApproach;
    private VerificationReportRepository reports;

    @BeforeEach
    void setUp() {
        SalatApiProperties props = new SalatApiProperties(dataDir);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        AtomicJsonFileWriter jsonFiles = new AtomicJsonFileWriter(mapper);
        Sha256Service sha = new Sha256Service();
        Clock clock = Clock.fixed(Instant.parse("2026-07-16T03:00:00Z"), ZoneOffset.UTC);

        var registry = new MoroccoCityRegistry();
        var params = new SecApproachParameters();
        var support = new CitySupportService(
                registry, new OfficialHabousCityDirectory(), new ApplicationHabousMapping(), params);
        var assembler = new GregorianArchiveAssembler(support, clock);
        archiveRepo = new JsonMonthlyArchiveRepository(props, jsonFiles, sha);
        var archives = new MonthlyArchiveService(assembler, archiveRepo);
        secApproach = new SecApproachCalculator(
                registry, new HabousRawEngine(), ReconstructedMethod.frozen(), params);
        reports = new VerificationReportRepository(props, jsonFiles);
        verification = new DailyVerificationService(
                archives, secApproach, new PrayerTimeComparisonService(), reports, clock);
    }

    /**
     * A city schedule with one official day for {@link #DATE}. For a sec-approach
     * city the "official" times are the sec-approach result (so agreement is exact);
     * for an official-only city fixed times are used, because sec-approach cannot —
     * and must not — produce times for it.
     */
    private MonthlyCitySchedule scheduleFromSecApproach(
            String villeId, String slug, boolean secApproachSupported) {
        PrayerTimes times = secApproachSupported
                ? secApproach.computeTimes(slug, DATE)
                : new PrayerTimes(DATE,
                        java.time.LocalTime.of(4, 30), java.time.LocalTime.of(6, 15),
                        java.time.LocalTime.of(13, 35), java.time.LocalTime.of(17, 15),
                        java.time.LocalTime.of(20, 45), java.time.LocalTime.of(22, 15));
        ArchivedDay day = new ArchivedDay(DATE,
                times.get(Prayer.FAJR), times.get(Prayer.SUNRISE), times.get(Prayer.DHUHR),
                times.get(Prayer.ASR), times.get(Prayer.MAGHRIB), times.get(Prayer.ISHA),
                "checksum", Instant.parse("2026-07-01T03:00:00Z"), Instant.parse("2026-07-01T03:00:00Z"));
        return new MonthlyCitySchedule(villeId, slug, slug, secApproachSupported,
                CityArchiveStatus.PARTIAL, 31, 1, List.of(day), null, List.of("checksum"));
    }

    private void storeArchive(MonthlyCitySchedule... cities) {
        List<MonthlyCitySchedule> list = new ArrayList<>(List.of(cities));
        GregorianMonthlyArchive archive = new GregorianMonthlyArchive(
                GregorianMonthlyArchive.SCHEMA_VERSION, GregorianMonthlyArchive.SOURCE_OFFICIAL,
                "2026-07", GregorianMonthlyArchive.PERIOD_TYPE_GREGORIAN_MONTH,
                GregorianMonthlyArchive.TIMEZONE, MonthlyArchiveStatus.PARTIAL,
                Instant.parse("2026-07-15T03:00:00Z"), Instant.parse("2026-07-15T03:00:00Z"),
                191, list.size(), 0, list.size(), 0, list);
        archiveRepo.save(archive, Instant.parse("2026-07-15T03:00:00Z"), List.of());
    }

    @Test
    void onlySecApproachCitiesWithOfficialDataAreCompared() {
        storeArchive(
                scheduleFromSecApproach("58", "casablanca", true),   // sec-approach + official → compared
                scheduleFromSecApproach("32", "berkane", false));    // official-only → excluded

        DailyVerificationReport report = verification.verify(DATE);

        assertThat(report.comparedCityCount()).isEqualTo(1);
        assertThat(report.cities()).singleElement().satisfies(c ->
                assertThat(c.applicationCityId()).isEqualTo("casablanca"));
    }

    @Test
    void anOfficialOnlyCityIsNeverComparedAgainstAGuessedSecApproachResult() {
        // berkane is mapped but has no offsets (one of the 12 official-only cities).
        // It has official data here, but must not be compared using guessed offsets.
        storeArchive(scheduleFromSecApproach("32", "berkane", false));

        DailyVerificationReport report = verification.verify(DATE);

        assertThat(report.comparedCityCount()).isZero();
        assertThat(report.cities()).isEmpty();
    }

    @Test
    void aSecApproachCityWithoutOfficialDataForTheDateIsNotCompared() {
        // casablanca is sec-approach, but its only official day is the 2nd, not the
        // 1st: no official row for the date means no comparison.
        MonthlyCitySchedule casa = scheduleFromSecApproach("58", "casablanca", true);
        PrayerTimes onSecond = secApproach.computeTimes("casablanca", LocalDate.of(2026, 7, 2));
        ArchivedDay secondOnly = new ArchivedDay(LocalDate.of(2026, 7, 2),
                onSecond.get(Prayer.FAJR), onSecond.get(Prayer.SUNRISE), onSecond.get(Prayer.DHUHR),
                onSecond.get(Prayer.ASR), onSecond.get(Prayer.MAGHRIB), onSecond.get(Prayer.ISHA),
                "c", Instant.parse("2026-07-02T03:00:00Z"), Instant.parse("2026-07-02T03:00:00Z"));
        MonthlyCitySchedule shifted = new MonthlyCitySchedule("58", "casablanca", "casablanca", true,
                CityArchiveStatus.PARTIAL, 31, 1, List.of(secondOnly), null, List.of("c"));
        storeArchive(shifted);

        assertThat(verification.verify(DATE).comparedCityCount()).isZero();
    }

    @Test
    void aSecApproachCityAgreeingExactlyCountsAsAnExactMatch() {
        // The "official" data was generated from sec-approach itself, so agreement
        // is exact — a clean check that the comparison plumbing is correct.
        storeArchive(scheduleFromSecApproach("81", "fez", true));

        DailyVerificationReport report = verification.verify(DATE);

        assertThat(report.comparedCityCount()).isEqualTo(1);
        assertThat(report.exactCityCount()).isEqualTo(1);
        assertThat(report.overall().exactRatio()).isEqualTo(1.0);
        assertThat(report.overall().sampleCount()).isEqualTo(6);   // six prayers
        assertThat(report.parameterVersion()).isEqualTo("morocco-habous-secaproach-v2");
        assertThat(report.accuracyDisclaimer()).contains("2026-06-17").contains("Not an annual");
    }

    @Test
    void aDisagreementIsRecordedWithSignedMinuteDeltas() {
        // Shift the official Fajr by +3 minutes so the comparison is non-exact.
        PrayerTimes times = secApproach.computeTimes("fez", DATE);
        ArchivedDay shifted = new ArchivedDay(DATE,
                times.get(Prayer.FAJR).plusMinutes(3), times.get(Prayer.SUNRISE), times.get(Prayer.DHUHR),
                times.get(Prayer.ASR), times.get(Prayer.MAGHRIB), times.get(Prayer.ISHA),
                "c", Instant.parse("2026-07-01T03:00:00Z"), Instant.parse("2026-07-01T03:00:00Z"));
        storeArchive(new MonthlyCitySchedule("81", "fez", "fez", true,
                CityArchiveStatus.PARTIAL, 31, 1, List.of(shifted), null, List.of("c")));

        DailyVerificationReport report = verification.verify(DATE);

        assertThat(report.exactCityCount()).isZero();
        assertThat(report.cities().get(0).exactMatch()).isFalse();
        // secApproach - official = -3 for Fajr.
        assertThat(report.cities().get(0).differences().get(0).deltaMinutes()).isEqualTo(-3);
        assertThat(report.cities().get(0).maxAbsDeltaMinutes()).isEqualTo(3);

        // Aggregate metrics: five of six prayers exact, one off by 3.
        assertThat(report.overall().exactCount()).isEqualTo(5);
        assertThat(report.overall().withinOneCount()).isEqualTo(5);
        assertThat(report.overall().maxAbsoluteDifference()).isEqualTo(3);
        assertThat(report.overall().meanAbsoluteDifference()).isEqualTo(0.5);   // 3/6
        assertThat(report.metricsByPrayer().get("fajr").exactCount()).isZero();
        assertThat(report.metricsByPrayer().get("dhuhr").exactCount()).isEqualTo(1);
        assertThat(report.metricsByCity().get("fez").maxAbsoluteDifference()).isEqualTo(3);
    }

    @Test
    void theComparisonNeverExceedsTheReleasedFortyEightCities() {
        // Even if the archive somehow flagged a non-trained slug as sec-approach,
        // the calculator refuses it, so the intersection can never exceed 48.
        assertThat(new SecApproachParameters().count()).isEqualTo(48);
        assertThat(secApproach.supports("sale")).isFalse();  // registry-only, unmapped
        assertThat(secApproach.supports("berkane")).isFalse(); // official-only, no offsets
    }

    @Test
    void theReportIsStored() {
        storeArchive(scheduleFromSecApproach("58", "casablanca", true));
        verification.verify(DATE);
        assertThat(reports.find(DATE)).isPresent();
    }

    @Test
    void aMonthWithNoArchiveYieldsAnEmptyReportRatherThanFailing() {
        DailyVerificationReport report = verification.verify(LocalDate.of(2026, 1, 1));
        assertThat(report.comparedCityCount()).isZero();
        assertThat(report.cities()).isEmpty();
    }

    @Test
    void verificationNeverChangesTheReleasedOffsets() {
        // Introduce a large disagreement, then verify twice. The sec-approach result
        // must be identical before and after — daily results never retrain offsets.
        PrayerTimes times = secApproach.computeTimes("fez", DATE);
        ArchivedDay wildlyOff = new ArchivedDay(DATE,
                times.get(Prayer.FAJR).plusMinutes(45), times.get(Prayer.SUNRISE),
                times.get(Prayer.DHUHR), times.get(Prayer.ASR), times.get(Prayer.MAGHRIB),
                times.get(Prayer.ISHA), "c",
                Instant.parse("2026-07-01T03:00:00Z"), Instant.parse("2026-07-01T03:00:00Z"));
        storeArchive(new MonthlyCitySchedule("81", "fez", "fez", true,
                CityArchiveStatus.PARTIAL, 31, 1, List.of(wildlyOff), null, List.of("c")));

        PrayerTimes before = secApproach.computeTimes("fez", DATE);
        verification.verify(DATE);
        verification.verify(DATE);
        PrayerTimes after = secApproach.computeTimes("fez", DATE);

        assertThat(after).isEqualTo(before);
        assertThat(secApproach.parameterVersion()).isEqualTo("morocco-habous-secaproach-v2");
    }

    @Test
    void officialChecksumsAreRecorded() {
        storeArchive(scheduleFromSecApproach("58", "casablanca", true));

        DailyVerificationReport report = verification.verify(DATE);

        assertThat(report.officialChecksums()).containsExactly("checksum");
    }
}
