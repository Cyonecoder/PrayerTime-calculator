package io.github.cyonecoder.prayertime.audit.optimization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParameterRangeTest {

    @Test
    void generatesInclusiveGridWithBothEndpoints() {
        ParameterRange range = ParameterRange.of("x", 0.0, 10.0, 1.0);
        assertThat(range.size()).isEqualTo(11);
        assertThat(range.values()).containsExactly(
                0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0);
    }

    @Test
    void decimalStepStaysExactWithNoFloatingDrift() {
        // A 0.001 step over the Fajr range must not drift (e.g. 17.000000004).
        ParameterRange range = ParameterRange.of("fajrAngle", 17.0, 20.5, 0.001);
        assertThat(range.size()).isEqualTo(3501);

        List<BigDecimal> decimals = range.decimalValues();
        assertThat(decimals.get(0)).isEqualByComparingTo("17.000");
        assertThat(decimals.get(1)).isEqualByComparingTo("17.001");
        assertThat(decimals.get(234)).isEqualByComparingTo("17.234");
        assertThat(decimals.get(decimals.size() - 1)).isEqualByComparingTo("20.500");
        // Every value has at most three decimal places — proof of exact stepping.
        assertThat(decimals).allSatisfy(d -> assertThat(d.stripTrailingZeros().scale()).isLessThanOrEqualTo(3));
    }

    @Test
    void supportsDescendingPhysicalRangeExpressedAsAscendingBounds() {
        // Chourouk altitude runs -0.70 .. -2.50; expressed ascending as [-2.50, -0.70].
        ParameterRange range = ParameterRange.of("sunriseAltitudeDegrees", -2.50, -0.70, 0.001);
        assertThat(range.size()).isEqualTo(1801);
        assertThat(range.decimalValues().get(0)).isEqualByComparingTo("-2.500");
        assertThat(range.decimalValues().get(1800)).isEqualByComparingTo("-0.700");
    }

    @Test
    void lastPointNeverOvershootsMax() {
        // 0 .. 10 step 3 -> {0,3,6,9}; 12 would overshoot and must be excluded.
        ParameterRange range = ParameterRange.of("x", 0.0, 10.0, 3.0);
        assertThat(range.values()).containsExactly(0.0, 3.0, 6.0, 9.0);
    }

    @Test
    void integerSecondRangeGeneratesEveryStep() {
        ParameterRange range = ParameterRange.of("dhuhrAdjustmentSeconds", 0, 600, 1);
        assertThat(range.size()).isEqualTo(601);
        assertThat(range.values().get(600)).isEqualTo(600.0);
    }

    @Test
    void rejectsNonPositiveStep() {
        assertThatThrownBy(() -> ParameterRange.of("x", 0, 10, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("step");
    }

    @Test
    void rejectsMaxBelowMin() {
        assertThatThrownBy(() -> ParameterRange.of("x", 10, 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max");
    }
}
