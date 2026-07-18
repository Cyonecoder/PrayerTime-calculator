package io.github.cyonecoder.prayertime.audit.secapproach;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Circular, midnight-safe minute arithmetic in the evaluator. */
class SecApproachMidnightSafetyTest {

    @Test
    void circularErrorTreatsMidnightWrapAsOneMinute() {
        // 00:00 vs 23:59 is one minute apart, not 1439.
        assertThat(SecApproachEvaluator.circularMinuteError(0, 1439)).isEqualTo(1);
        assertThat(SecApproachEvaluator.circularMinuteError(1439, 0)).isEqualTo(-1);
        assertThat(SecApproachEvaluator.circularMinuteError(0, 0)).isZero();
        assertThat(SecApproachEvaluator.circularMinuteError(1, 1439)).isEqualTo(2);
    }

    @Test
    void circularErrorIsBoundedToPlusMinusHalfDay() {
        for (int predicted = 0; predicted < 1440; predicted++) {
            int err = SecApproachEvaluator.circularMinuteError(predicted, 0);
            assertThat(err).isBetween(-720, 719);
        }
    }

    @Test
    void predictedMinuteWrapsAcrossMidnight() {
        // 23:59:40 + 30s -> 00:00:10 -> minute 0.
        long raw = 23 * 3600 + 59 * 60 + 40;
        assertThat(SecApproachEvaluator.predictedMinute(raw, 30)).isZero();
        // 00:00:20 - 30s -> 23:59:50 -> minute 1439.
        assertThat(SecApproachEvaluator.predictedMinute(20, -30)).isEqualTo(1439);
    }

    @Test
    void evaluateNearMidnightCountsExactAndWrapsError() {
        var days = java.util.List.of(
                new SecApproachTrainingDataset.TrainingObservation(
                        "x", java.time.LocalDate.of(2026, 6, 17),
                        io.github.cyonecoder.prayertime.domain.model.Prayer.ISHA,
                        23 * 3600 + 59 * 60 + 40, 0, "UNASSIGNED", "sha"));
        // +30s published minute 0 == official minute 0 -> exact.
        assertThat(SecApproachEvaluator.evaluate(30, days).exactCount()).isEqualTo(1);
        // +0s published minute 1439 vs official 0 -> off by one circular minute.
        assertThat(SecApproachEvaluator.evaluate(0, days).maxAbsoluteError()).isEqualTo(1);
    }
}
