package io.github.cyonecoder.prayertime.audit.secapproach;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachTrainingDataset.TrainingObservation;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The deterministic lexicographic tie-break, level by level. */
class SecApproachTieBreakingTest {

    private static SecApproachMetrics m(
            int offset, int exact, int within1, long sumAbs, int maxAbs) {
        return new SecApproachMetrics(offset, 30, exact, within1, sumAbs, maxAbs, 0, Map.of());
    }

    @Test
    void higherExactCountWinsFirst() {
        assertThat(SecApproachMetrics.BEST_FIRST.compare(m(5, 30, 30, 0, 0), m(0, 29, 30, 1, 1)))
                .isLessThan(0);
    }

    @Test
    void thenHigherWithinOneCount() {
        assertThat(SecApproachMetrics.BEST_FIRST.compare(m(5, 20, 30, 40, 2), m(0, 20, 25, 40, 2)))
                .isLessThan(0);
    }

    @Test
    void thenLowerMeanAbsoluteErrorThenMaxThenAbsThenSignedOffset() {
        // equal exact + within1: lower sumAbs wins.
        assertThat(SecApproachMetrics.BEST_FIRST.compare(m(5, 20, 20, 10, 2), m(0, 20, 20, 20, 2)))
                .isLessThan(0);
        // equal through maxAbs: smaller |offset| wins.
        assertThat(SecApproachMetrics.BEST_FIRST.compare(m(3, 20, 20, 10, 2), m(-7, 20, 20, 10, 2)))
                .isLessThan(0);
        // equal |offset|: the negative (lower signed) offset wins.
        assertThat(SecApproachMetrics.BEST_FIRST.compare(m(-4, 20, 20, 10, 2), m(4, 20, 20, 10, 2)))
                .isLessThan(0);
    }

    @Test
    void tiedCandidatesResolveDeterministicallyToTheCentredSmallestOffset() {
        // raw 13:00:00, official 13:00: offsets [0,59] all give 30 exact; the smallest
        // |offset| wins -> 0. Fully deterministic regardless of iteration.
        List<TrainingObservation> days = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            days.add(new TrainingObservation("x", LocalDate.of(2026, 6, 17).plusDays(i),
                    Prayer.DHUHR, 13 * 3600, 780, "UNASSIGNED", "sha"));
        }
        SecApproachOffsetSearch.Result a = SecApproachOffsetSearch.search(days);
        SecApproachOffsetSearch.Result b = SecApproachOffsetSearch.search(days);
        assertThat(a.best().offset()).isEqualTo(0).isEqualTo(b.best().offset());
        assertThat(a.best().exactCount()).isEqualTo(30);
    }
}
