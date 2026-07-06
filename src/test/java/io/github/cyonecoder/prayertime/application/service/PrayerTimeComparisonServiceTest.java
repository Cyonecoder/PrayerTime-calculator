package io.github.cyonecoder.prayertime.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.application.service.PrayerTimeComparisonService.ComparisonResult;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimesResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PrayerTimeComparisonServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 15);
    private final PrayerTimeComparisonService service = new PrayerTimeComparisonService();

    private static PrayerTimes times(
            String fajr, String sunrise, String dhuhr, String asr, String maghrib, String isha) {
        return new PrayerTimes(DATE,
                LocalTime.parse(fajr), LocalTime.parse(sunrise), LocalTime.parse(dhuhr),
                LocalTime.parse(asr), LocalTime.parse(maghrib), LocalTime.parse(isha));
    }

    private static PrayerTimesResult official(PrayerTimes times) {
        return new PrayerTimesResult(times, "habous-official", "Habous Official",
                Instant.parse("2026-07-01T09:00:00Z"), ZoneId.of("Africa/Casablanca"),
                ZoneOffset.ofHours(1));
    }

    @Test
    void reportsUnavailableOfficialDataDistinctly() {
        PrayerTimes calc = times("05:00", "06:30", "13:30", "17:00", "20:15", "21:45");

        ComparisonResult result = service.compare(calc, "astronomical", Optional.empty());

        assertThat(result).isInstanceOf(ComparisonResult.OfficialUnavailable.class);
        ComparisonResult.OfficialUnavailable unavailable = (ComparisonResult.OfficialUnavailable) result;
        assertThat(unavailable.calculatedSource()).isEqualTo("astronomical");
        assertThat(unavailable.reason()).contains("No official Habous data");
    }

    @Test
    void keepsCalculatedAndOfficialSourceLabelsDistinct() {
        PrayerTimes calc = times("05:00", "06:30", "13:30", "17:00", "20:15", "21:45");
        PrayerTimes off = times("05:00", "06:30", "13:30", "17:00", "20:15", "21:45");

        ComparisonResult.Compared report = (ComparisonResult.Compared)
                service.compare(calc, "astronomical", Optional.of(official(off)));

        assertThat(report.calculatedSource()).isEqualTo("astronomical");
        assertThat(report.officialSource()).isEqualTo("habous-official");
        assertThat(report.calculatedSource()).isNotEqualTo(report.officialSource());
    }

    @Test
    void allWithinToleranceWhenTheFivePrayersMatch() {
        PrayerTimes calc = times("05:01", "06:30", "13:31", "17:01", "20:16", "21:46");
        PrayerTimes off = times("05:00", "06:30", "13:30", "17:00", "20:15", "21:45");

        ComparisonResult.Compared report = (ComparisonResult.Compared)
                service.compare(calc, "astronomical", Optional.of(official(off)));

        assertThat(report.allWithinTolerance()).isTrue();
        assertThat(report.maxAbsoluteErrorMinutes()).isEqualTo(1);
        assertThat(report.meanAbsoluteErrorMinutes()).isEqualTo(1.0); // all five off by 1
        assertThat(report.prayerComparisons()).hasSize(5);
    }

    @Test
    void failsWhenAnObligatoryPrayerExceedsTolerance() {
        PrayerTimes calc = times("05:05", "06:30", "13:30", "17:00", "20:15", "21:45"); // Fajr +5
        PrayerTimes off = times("05:00", "06:30", "13:30", "17:00", "20:15", "21:45");

        ComparisonResult.Compared report = (ComparisonResult.Compared)
                service.compare(calc, "astronomical", Optional.of(official(off)));

        assertThat(report.allWithinTolerance()).isFalse();
        assertThat(report.maxAbsoluteErrorMinutes()).isEqualTo(5);
    }

    @Test
    void sunriseIsReportedSeparatelyAndDoesNotAffectPassFail() {
        // Five prayers match exactly, but Sunrise is off by 30 minutes.
        PrayerTimes calc = times("05:00", "07:00", "13:30", "17:00", "20:15", "21:45");
        PrayerTimes off = times("05:00", "06:30", "13:30", "17:00", "20:15", "21:45");

        ComparisonResult.Compared report = (ComparisonResult.Compared)
                service.compare(calc, "astronomical", Optional.of(official(off)));

        assertThat(report.allWithinTolerance()).isTrue();          // five prayers still pass
        assertThat(report.maxAbsoluteErrorMinutes()).isZero();     // sunrise excluded from aggregates
        assertThat(report.sunriseComparison().absoluteDifferenceMinutes()).isEqualTo(30);
        assertThat(report.sunriseComparison().withinTolerance()).isFalse();
    }
}
