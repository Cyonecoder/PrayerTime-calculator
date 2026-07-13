package io.github.cyonecoder.prayertime.audit.secapproach;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachChronologicalSplit.Segment;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecApproachChronologicalSplitTest {

    private static List<LocalDate> window(String from, int days) {
        List<LocalDate> out = new ArrayList<>();
        LocalDate d = LocalDate.parse(from);
        for (int i = 0; i < days; i++) {
            out.add(d.plusDays(i));
        }
        return out;
    }

    @Test
    void thirtyDayWindowSplitsIntoTheSpecifiedDates() {
        Map<LocalDate, Segment> s = SecApproachChronologicalSplit.assign(window("2026-06-17", 30));

        long training = s.values().stream().filter(v -> v == Segment.TRAINING).count();
        long validation = s.values().stream().filter(v -> v == Segment.VALIDATION).count();
        long holdout = s.values().stream().filter(v -> v == Segment.HOLDOUT).count();
        assertThat(training).isEqualTo(20);
        assertThat(validation).isEqualTo(5);
        assertThat(holdout).isEqualTo(5);

        // Exact boundaries per the spec.
        assertThat(s.get(LocalDate.parse("2026-06-17"))).isEqualTo(Segment.TRAINING);
        assertThat(s.get(LocalDate.parse("2026-07-06"))).isEqualTo(Segment.TRAINING);
        assertThat(s.get(LocalDate.parse("2026-07-07"))).isEqualTo(Segment.VALIDATION);
        assertThat(s.get(LocalDate.parse("2026-07-11"))).isEqualTo(Segment.VALIDATION);
        assertThat(s.get(LocalDate.parse("2026-07-12"))).isEqualTo(Segment.HOLDOUT);
        assertThat(s.get(LocalDate.parse("2026-07-16"))).isEqualTo(Segment.HOLDOUT);
    }

    @Test
    void otherWindowLengthsSplitProportionallyAndDeterministically() {
        // 12 days -> train 8 (2/3), validation 2 (1/6), holdout 2 (1/6).
        Map<LocalDate, Segment> s = SecApproachChronologicalSplit.assign(window("2026-01-01", 12));
        assertThat(s.values().stream().filter(v -> v == Segment.TRAINING).count()).isEqualTo(8);
        assertThat(s.values().stream().filter(v -> v == Segment.VALIDATION).count()).isEqualTo(2);
        assertThat(s.values().stream().filter(v -> v == Segment.HOLDOUT).count()).isEqualTo(2);
        // Chronological: the last date is always holdout, the first always training.
        assertThat(s.get(LocalDate.parse("2026-01-01"))).isEqualTo(Segment.TRAINING);
        assertThat(s.get(LocalDate.parse("2026-01-12"))).isEqualTo(Segment.HOLDOUT);
    }
}
