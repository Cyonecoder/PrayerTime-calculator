package io.github.cyonecoder.prayertime.audit.secapproach;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.optimization.HabousRawEngine;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachChronologicalSplit.Segment;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachSplitEvaluator.CitySplitEvaluation;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachSplitEvaluator.PrayerSplitEvaluation;
import io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Chronological split boundaries and fit-on-train / score-on-holdout behaviour. */
class SecApproachTrainingSplitTest {

    private static List<LocalDate> window(String from, int days) {
        List<LocalDate> out = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            out.add(LocalDate.parse(from).plusDays(i));
        }
        return out;
    }

    @Test
    void thirtyDayWindowSplitsTwentyFiveFiveOnTheSpecifiedDates() {
        Map<LocalDate, Segment> s = SecApproachChronologicalSplit.assign(window("2026-06-17", 30));
        assertThat(s.values().stream().filter(v -> v == Segment.TRAINING).count()).isEqualTo(20);
        assertThat(s.values().stream().filter(v -> v == Segment.VALIDATION).count()).isEqualTo(5);
        assertThat(s.values().stream().filter(v -> v == Segment.HOLDOUT).count()).isEqualTo(5);
        assertThat(s.get(LocalDate.parse("2026-07-06"))).isEqualTo(Segment.TRAINING);
        assertThat(s.get(LocalDate.parse("2026-07-07"))).isEqualTo(Segment.VALIDATION);
        assertThat(s.get(LocalDate.parse("2026-07-11"))).isEqualTo(Segment.VALIDATION);
        assertThat(s.get(LocalDate.parse("2026-07-12"))).isEqualTo(Segment.HOLDOUT);
        assertThat(s.get(LocalDate.parse("2026-07-16"))).isEqualTo(Segment.HOLDOUT);
    }

    @Test
    void otherLengthsSplitProportionallyFirstTwoThirdsNextSixthFinalSixth() {
        Map<LocalDate, Segment> s = SecApproachChronologicalSplit.assign(window("2026-01-01", 12));
        assertThat(s.values().stream().filter(v -> v == Segment.TRAINING).count()).isEqualTo(8);
        assertThat(s.values().stream().filter(v -> v == Segment.VALIDATION).count()).isEqualTo(2);
        assertThat(s.values().stream().filter(v -> v == Segment.HOLDOUT).count()).isEqualTo(2);
        assertThat(s.get(LocalDate.parse("2026-01-01"))).isEqualTo(Segment.TRAINING);
        assertThat(s.get(LocalDate.parse("2026-01-12"))).isEqualTo(Segment.HOLDOUT);
    }

    @Test
    void holdoutIsScoredWithTheTrainingSelectedOffsetNotRefit() {
        SecApproachTrainingDataset ds = SecApproachTrainingDataset.fromDirectory(
                Path.of("research-data/habous"), new MoroccoCityRegistry(), new HabousRawEngine());
        CitySplitEvaluation e = new SecApproachSplitEvaluator(-900, 900).evaluate(ds, "safi");
        for (PrayerSplitEvaluation p : e.prayers()) {
            assertThat(p.training().observations()).isEqualTo(20);
            assertThat(p.validation().observations()).isEqualTo(5);
            assertThat(p.holdout().observations()).isEqualTo(5);
            assertThat(p.inSample().observations()).isEqualTo(30);
            // Holdout uses the split (train-selected) offset; the release offset is the
            // in-sample all-dates optimum and may differ.
            assertThat(p.holdout().offset()).isEqualTo(p.splitOffset());
        }
    }
}
