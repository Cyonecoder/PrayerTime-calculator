package io.github.cyonecoder.prayertime.audit.optimization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RoundingPolicyTest {

    @Test
    void floorAlwaysRoundsDown() {
        assertThat(RoundingPolicy.FLOOR.roundToWholeMinutes(1.9)).isEqualTo(1);
        assertThat(RoundingPolicy.FLOOR.roundToWholeMinutes(-1.1)).isEqualTo(-2);
    }

    @Test
    void ceilingAlwaysRoundsUp() {
        assertThat(RoundingPolicy.CEILING.roundToWholeMinutes(1.1)).isEqualTo(2);
        assertThat(RoundingPolicy.CEILING.roundToWholeMinutes(-1.9)).isEqualTo(-1);
    }

    @Test
    void truncateDropsFractionTowardZero() {
        assertThat(RoundingPolicy.TRUNCATE.roundToWholeMinutes(1.9)).isEqualTo(1);
        assertThat(RoundingPolicy.TRUNCATE.roundToWholeMinutes(-1.9)).isEqualTo(-1);
    }

    @Test
    void nearestMinuteBreaksTiesTowardPositiveInfinity() {
        assertThat(RoundingPolicy.NEAREST_MINUTE.roundToWholeMinutes(1.4)).isEqualTo(1);
        assertThat(RoundingPolicy.NEAREST_MINUTE.roundToWholeMinutes(1.5)).isEqualTo(2);
        assertThat(RoundingPolicy.NEAREST_MINUTE.roundToWholeMinutes(2.5)).isEqualTo(3);
        // Distinguishes it from HALF_UP: a negative tie rounds toward +infinity.
        assertThat(RoundingPolicy.NEAREST_MINUTE.roundToWholeMinutes(-1.5)).isEqualTo(-1);
    }

    @Test
    void halfUpBreaksTiesAwayFromZero() {
        assertThat(RoundingPolicy.HALF_UP.roundToWholeMinutes(1.5)).isEqualTo(2);
        assertThat(RoundingPolicy.HALF_UP.roundToWholeMinutes(2.5)).isEqualTo(3);
        assertThat(RoundingPolicy.HALF_UP.roundToWholeMinutes(-1.5)).isEqualTo(-2);
    }

    @Test
    void halfEvenBreaksTiesToTheEvenMinute() {
        assertThat(RoundingPolicy.HALF_EVEN.roundToWholeMinutes(1.5)).isEqualTo(2);
        assertThat(RoundingPolicy.HALF_EVEN.roundToWholeMinutes(2.5)).isEqualTo(2);
        assertThat(RoundingPolicy.HALF_EVEN.roundToWholeMinutes(3.5)).isEqualTo(4);
    }

    @Test
    void everyPolicyIsExercisedAndFiniteCheckHolds() {
        for (RoundingPolicy policy : RoundingPolicy.values()) {
            assertThat(policy.roundToWholeMinutes(10.0)).isEqualTo(10);
            assertThatThrownBy(() -> policy.roundToWholeMinutes(Double.NaN))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
