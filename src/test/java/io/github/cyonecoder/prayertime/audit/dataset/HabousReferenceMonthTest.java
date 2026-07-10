package io.github.cyonecoder.prayertime.audit.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HabousReferenceMonthTest {

    private static HabousReferenceDay day(LocalDate date) {
        return new HabousReferenceDay(date,
                LocalTime.of(5, 0), LocalTime.of(6, 0), LocalTime.of(13, 0),
                LocalTime.of(17, 0), LocalTime.of(20, 0), LocalTime.of(22, 0));
    }

    private static HabousReferenceMonth month(int year, int monthValue, int daysPresent) {
        List<HabousReferenceDay> days = new ArrayList<>();
        for (int d = 1; d <= daysPresent; d++) {
            days.add(day(LocalDate.of(year, monthValue, d)));
        }
        return new HabousReferenceMonth("casablanca", "الدار البيضاء", "الدار البيضاء",
                year, monthValue, Instant.EPOCH, "https://example/ville", "checksum", "v1", days);
    }

    @Test
    void completeMonthHasNoMissingDays() {
        HabousReferenceMonth july = month(2026, 7, 31);
        assertThat(july.isComplete()).isTrue();
        assertThat(july.missingDays()).isEmpty();
        assertThat(july.yearMonth()).isEqualTo(java.time.YearMonth.of(2026, 7));
    }

    @Test
    void incompleteMonthReportsMissingDays() {
        HabousReferenceMonth partial = month(2026, 7, 29);
        assertThat(partial.isComplete()).isFalse();
        assertThat(partial.missingDays())
                .containsExactly(LocalDate.of(2026, 7, 30), LocalDate.of(2026, 7, 31));
    }

    @Test
    void leapFebruaryHasTwentyNineCompleteDays() {
        HabousReferenceMonth feb = month(2024, 2, 29);
        assertThat(feb.isComplete()).isTrue();
        assertThat(feb.dailyRecords()).hasSize(29);
    }

    @Test
    void rejectsDayOutsideTheStatedMonth() {
        List<HabousReferenceDay> days = List.of(day(LocalDate.of(2026, 8, 1)));
        assertThatThrownBy(() -> new HabousReferenceMonth("casablanca", "x", "x",
                2026, 7, Instant.EPOCH, "u", "c", "v", days))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");
    }

    @Test
    void rejectsDuplicateDay() {
        List<HabousReferenceDay> days = List.of(
                day(LocalDate.of(2026, 7, 1)), day(LocalDate.of(2026, 7, 1)));
        assertThatThrownBy(() -> new HabousReferenceMonth("casablanca", "x", "x",
                2026, 7, Instant.EPOCH, "u", "c", "v", days))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void rejectsInvalidMonthNumber() {
        // A valid January day, but month=13 must be rejected by the record itself.
        List<HabousReferenceDay> days = List.of(day(LocalDate.of(2026, 1, 1)));
        assertThatThrownBy(() -> new HabousReferenceMonth("casablanca", "x", "x",
                2026, 13, Instant.EPOCH, "u", "c", "v", days))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("month");
    }
}
