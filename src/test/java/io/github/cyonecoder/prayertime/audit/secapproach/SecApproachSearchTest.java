package io.github.cyonecoder.prayertime.audit.secapproach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachTrainingDataset.TrainingObservation;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Evaluator arithmetic, metric ranking, and the deterministic bounded search. */
class SecApproachSearchTest {

    private static TrainingObservation obs(long rawSeconds, int officialMinute) {
        return new TrainingObservation("x", LocalDate.of(2026, 7, 1), Prayer.DHUHR,
                rawSeconds, officialMinute, "UNASSIGNED", "sha");
    }

    @Test
    void predictedMinuteFloorsRawPlusOffset() {
        assertThat(SecApproachEvaluator.predictedMinute(13 * 3600, 0)).isEqualTo(13 * 60);
        // 13:00:59 + 1s = 13:01:00 -> minute 781.
        assertThat(SecApproachEvaluator.predictedMinute(13 * 3600 + 59, 1)).isEqualTo(13 * 60 + 1);
    }

    @Test
    void circularErrorIsMidnightSafe() {
        assertThat(SecApproachEvaluator.circularMinuteError(0, 1439)).isEqualTo(1);
        assertThat(SecApproachEvaluator.circularMinuteError(1439, 0)).isEqualTo(-1);
        assertThat(SecApproachEvaluator.circularMinuteError(780, 780)).isZero();
    }

    @Test
    void evaluateCountsExactWithinOneAndHistogram() {
        // Three days, all at 13:00 raw; official 13:00, 13:00, 13:01.
        List<TrainingObservation> days = List.of(
                obs(13 * 3600, 780), obs(13 * 3600, 780), obs(13 * 3600, 781));
        SecApproachMetrics m = SecApproachEvaluator.evaluate(0, days);
        assertThat(m.exactCount()).isEqualTo(2);
        assertThat(m.withinOneCount()).isEqualTo(3);
        assertThat(m.maxAbsoluteError()).isEqualTo(1);
        assertThat(m.sumAbsoluteError()).isEqualTo(1);
        assertThat(m.errorHistogram()).containsEntry(0, 2).containsEntry(-1, 1);
    }

    @Test
    void searchFindsTheOffsetThatMaximisesExact() {
        // Raw 12:59:30 every day; official 13:00 -> needs +30s to reach minute 780.
        List<TrainingObservation> days = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            days.add(obs(13 * 3600 - 30, 780));
        }
        SecApproachOffsetSearch.Result r = SecApproachOffsetSearch.search(days);
        assertThat(r.best().exactCount()).isEqualTo(30);
        // Among all offsets giving 30 exact ([30..89]), the tie-break keeps the
        // smallest absolute offset: 30.
        assertThat(r.best().offset()).isEqualTo(30);
        assertThat(r.touchesBoundary()).isFalse();
    }

    @Test
    void searchFlagsBoundaryWhenBestSitsOnTheEdge() {
        // Raw 13:00:55; only offset +5s reaches minute 781, so the sole exact-optimal
        // offset in [-5,5] is the high edge -> boundary touched.
        List<TrainingObservation> days = List.of(obs(13 * 3600 + 55, 781));
        SecApproachOffsetSearch.Result r = SecApproachOffsetSearch.search(days, -5, 5);
        assertThat(r.best().offset()).isEqualTo(5);
        assertThat(r.touchesBoundary()).isTrue();
    }

    @Test
    void rankingIsLexicographicExactThenWithinOneThenErrorThenOffset() {
        // a: more exact wins outright.
        SecApproachMetrics more = SecApproachEvaluator.evaluate(0,
                List.of(obs(13 * 3600, 780), obs(13 * 3600, 780)));
        SecApproachMetrics fewer = SecApproachEvaluator.evaluate(60,
                List.of(obs(13 * 3600, 780), obs(13 * 3600, 780)));
        assertThat(SecApproachMetrics.BEST_FIRST.compare(more, fewer)).isLessThan(0);
    }

    @Test
    void emptySearchIsRejected() {
        assertThatThrownBy(() -> SecApproachOffsetSearch.search(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
