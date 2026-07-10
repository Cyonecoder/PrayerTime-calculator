package io.github.cyonecoder.prayertime.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.application.service.PrayerComparison;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrayerTimesValidatorTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 15);
    private final PrayerTimesValidator validator = new PrayerTimesValidator();

    private static PrayerTimes valid() {
        return new PrayerTimes(DATE,
                LocalTime.of(5, 0), LocalTime.of(6, 30), LocalTime.of(13, 30),
                LocalTime.of(17, 0), LocalTime.of(20, 15), LocalTime.of(21, 45));
    }

    @Test
    void validTimesProduceNoIssues() {
        assertThat(validator.validate(valid())).isEmpty();
    }

    @Test
    void detectsOutOfOrderTimes() {
        // Sunrise before Fajr.
        PrayerTimes bad = new PrayerTimes(DATE,
                LocalTime.of(6, 0), LocalTime.of(5, 0), LocalTime.of(13, 30),
                LocalTime.of(17, 0), LocalTime.of(20, 15), LocalTime.of(21, 45));

        List<ValidationIssue> issues = validator.validate(bad);
        assertThat(issues).extracting(ValidationIssue::code)
                .contains("ORDERING", "FAJR_BEFORE_SUNRISE");
    }

    @Test
    void detectsAsrOutsideDhuhrMaghribWindow() {
        // Asr (13:00) before Dhuhr (13:30).
        PrayerTimes bad = new PrayerTimes(DATE,
                LocalTime.of(5, 0), LocalTime.of(6, 30), LocalTime.of(13, 30),
                LocalTime.of(13, 0), LocalTime.of(20, 15), LocalTime.of(21, 45));

        assertThat(validator.validate(bad)).extracting(ValidationIssue::code)
                .contains("ASR_AFTER_DHUHR");
    }

    @Test
    void detectsDhuhrBeforeSolarNoonAndMaghribBeforeSunset() {
        PrayerTimes times = valid();
        // solar noon after Dhuhr and sunset after Maghrib => both invalid.
        List<ValidationIssue> issues =
                validator.validate(times, LocalTime.of(13, 35), LocalTime.of(20, 20));

        assertThat(issues).extracting(ValidationIssue::code)
                .contains("DHUHR_AFTER_SOLAR_NOON", "MAGHRIB_AFTER_SUNSET");
    }

    @Test
    void astronomicalOverloadPassesWhenReferencesAreConsistent() {
        assertThat(validator.validate(valid(), LocalTime.of(13, 25), LocalTime.of(20, 10)))
                .isEmpty();
    }

    @Test
    void flagsToleranceViolations() {
        List<PrayerComparison> comparisons = List.of(
                PrayerComparison.of(Prayer.FAJR, LocalTime.of(5, 0), LocalTime.of(5, 0), 2),
                PrayerComparison.of(Prayer.ISHA, LocalTime.of(21, 50), LocalTime.of(21, 45), 2));

        List<ValidationIssue> issues = validator.validateTolerances(comparisons);
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).code()).isEqualTo("TOLERANCE");
        assertThat(issues.get(0).message()).contains("ISHA");
    }
}
