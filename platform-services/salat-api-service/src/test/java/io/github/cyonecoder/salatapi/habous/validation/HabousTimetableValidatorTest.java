package io.github.cyonecoder.salatapi.habous.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.salatapi.habous.model.HabousPublishedDay;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Semantic validation of a parsed timetable (spec 20, 28): dates must be
 * contiguous and every day must carry all six prayers.
 */
class HabousTimetableValidatorTest {

    private final HabousTimetableValidator validator = new HabousTimetableValidator();

    private static HabousPublishedDay day(LocalDate date) {
        return new HabousPublishedDay(date, LocalTime.of(4, 30), LocalTime.of(6, 15),
                LocalTime.of(13, 35), LocalTime.of(17, 15), LocalTime.of(20, 45), LocalTime.of(22, 15));
    }

    @Test
    void aContiguousSixColumnMonthIsValid() {
        List<HabousPublishedDay> days = List.of(
                day(LocalDate.of(2026, 7, 1)), day(LocalDate.of(2026, 7, 2)), day(LocalDate.of(2026, 7, 3)));

        assertThat(validator.validate(days).valid()).isTrue();
    }

    @Test
    void aGapInTheDatesIsRejected() {
        List<HabousPublishedDay> days = List.of(
                day(LocalDate.of(2026, 7, 1)), day(LocalDate.of(2026, 7, 3)));  // missing the 2nd

        var report = validator.validate(days);
        assertThat(report.valid()).isFalse();
        assertThat(report.problems()).anyMatch(p -> p.contains("not contiguous"));
    }

    @Test
    void aDuplicateDateIsRejected() {
        List<HabousPublishedDay> days = List.of(
                day(LocalDate.of(2026, 7, 1)), day(LocalDate.of(2026, 7, 1)));  // same date twice

        var report = validator.validate(days);
        assertThat(report.valid()).isFalse();
        assertThat(report.problems()).anyMatch(p -> p.contains("not contiguous"));
    }

    @Test
    void aReversedDateOrderIsRejected() {
        List<HabousPublishedDay> days = List.of(
                day(LocalDate.of(2026, 7, 2)), day(LocalDate.of(2026, 7, 1)));

        assertThat(validator.validate(days).valid()).isFalse();
    }

    @Test
    void anEmptyTimetableIsRejected() {
        var report = validator.validate(List.of());
        assertThat(report.valid()).isFalse();
        assertThat(report.problems()).anyMatch(p -> p.contains("no days"));
    }
}
