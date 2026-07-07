package io.github.cyonecoder.prayertime.audit.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OperationOrderTest {

    // 06:22:20 = 382.333 minutes = 22940 seconds of day.
    private static final double RAW_06_22_20 = 22_940.0;

    @Test
    void adjustThenRoundAddsBeforeRounding() {
        // (22940 + 40) / 60 = 383.0 exactly -> 383.
        int minute = OperationOrder.RAW_THEN_ADJUST_THEN_ROUND
                .publishedMinuteOfDay(RAW_06_22_20, 40, RoundingPolicy.NEAREST_MINUTE);
        assertThat(minute).isEqualTo(383);
    }

    @Test
    void roundThenAdjustRoundsBeforeAdding() {
        // round(382.333)=382 -> 382*60 + 40 = 22960 -> floor(382.666) = 382.
        int minute = OperationOrder.RAW_THEN_ROUND_THEN_ADJUST
                .publishedMinuteOfDay(RAW_06_22_20, 40, RoundingPolicy.NEAREST_MINUTE);
        assertThat(minute).isEqualTo(382);
    }

    @Test
    void adjustAndRoundThenAdjustOrdersDifferForTheSameInputs() {
        int adjustThenRound = OperationOrder.RAW_THEN_ADJUST_THEN_ROUND
                .publishedMinuteOfDay(RAW_06_22_20, 40, RoundingPolicy.NEAREST_MINUTE);
        int roundThenAdjust = OperationOrder.RAW_THEN_ROUND_THEN_ADJUST
                .publishedMinuteOfDay(RAW_06_22_20, 40, RoundingPolicy.NEAREST_MINUTE);
        assertThat(adjustThenRound).isNotEqualTo(roundThenAdjust);
    }

    @Test
    void truncateSecondsVariantDropsSubSecondPrecisionThenMatchesAdjustThenRoundMidMinute() {
        double rawWithSubSecond = RAW_06_22_20 + 0.75; // 22940.75
        int truncated = OperationOrder.RAW_THEN_TRUNCATE_SECONDS_THEN_ADJUST_THEN_ROUND
                .publishedMinuteOfDay(rawWithSubSecond, 40, RoundingPolicy.NEAREST_MINUTE);
        // floor(22940.75)=22940 -> (22940+40)/60 = 383.0 -> 383.
        assertThat(truncated).isEqualTo(383);
    }

    @Test
    void normalizesAcrossMidnight() {
        double raw = 23 * 3600 + 59 * 60 + 30; // 23:59:30
        int minute = OperationOrder.RAW_THEN_ADJUST_THEN_ROUND
                .publishedMinuteOfDay(raw, 60, RoundingPolicy.NEAREST_MINUTE);
        // (86370 + 60)/60 = 1440.5 -> round 1441 -> mod 1440 = 1 (00:01).
        assertThat(minute).isEqualTo(1);
    }

    @Test
    void everyOrderProducesAValidMinuteOfDay() {
        for (OperationOrder order : OperationOrder.values()) {
            int minute = order.publishedMinuteOfDay(RAW_06_22_20, 0, RoundingPolicy.NEAREST_MINUTE);
            assertThat(minute).isBetween(0, 1439);
        }
    }
}
