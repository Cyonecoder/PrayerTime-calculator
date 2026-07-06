package io.github.cyonecoder.prayertime.domain.method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class CalculationParametersTest {

    private static CalculationParameters params(
            double fajr, double isha, double asr, double sunrise) {
        return new CalculationParameters(
                fajr, isha, asr, sunrise, PrayerAdjustments.NONE, ElevationPolicy.OBSERVER_ELEVATION);
    }

    @Test
    void constructsWithValidValues() {
        CalculationParameters p = params(19.0, 17.0, 1.0, -0.8333);
        assertThat(p.fajrAngle()).isEqualTo(19.0);
        assertThat(p.elevationPolicy()).isEqualTo(ElevationPolicy.OBSERVER_ELEVATION);
    }

    @Test
    void rejectsFajrAngleOutOfRange() {
        assertThatIllegalArgumentException().isThrownBy(() -> params(0.0, 17.0, 1.0, -0.8333));
        assertThatIllegalArgumentException().isThrownBy(() -> params(-1.0, 17.0, 1.0, -0.8333));
        assertThatIllegalArgumentException().isThrownBy(() -> params(90.0, 17.0, 1.0, -0.8333));
    }

    @Test
    void rejectsIshaAngleOutOfRange() {
        assertThatIllegalArgumentException().isThrownBy(() -> params(19.0, 0.0, 1.0, -0.8333));
        assertThatIllegalArgumentException().isThrownBy(() -> params(19.0, 95.0, 1.0, -0.8333));
    }

    @Test
    void rejectsNonFiniteAngles() {
        assertThatIllegalArgumentException().isThrownBy(() -> params(Double.NaN, 17.0, 1.0, -0.8333));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> params(19.0, Double.POSITIVE_INFINITY, 1.0, -0.8333));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> params(19.0, 17.0, 1.0, Double.NaN));
    }

    @Test
    void rejectsNonPositiveShadowFactor() {
        assertThatIllegalArgumentException().isThrownBy(() -> params(19.0, 17.0, 0.0, -0.8333));
        assertThatIllegalArgumentException().isThrownBy(() -> params(19.0, 17.0, -1.0, -0.8333));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> params(19.0, 17.0, Double.NaN, -0.8333));
    }

    @Test
    void rejectsSunriseAngleOutOfRange() {
        assertThatIllegalArgumentException().isThrownBy(() -> params(19.0, 17.0, 1.0, -91.0));
        assertThatIllegalArgumentException().isThrownBy(() -> params(19.0, 17.0, 1.0, 91.0));
    }

    @Test
    void rejectsNullComponents() {
        assertThatNullPointerException().isThrownBy(() -> new CalculationParameters(
                19.0, 17.0, 1.0, -0.8333, null, ElevationPolicy.OBSERVER_ELEVATION));
        assertThatNullPointerException().isThrownBy(() -> new CalculationParameters(
                19.0, 17.0, 1.0, -0.8333, PrayerAdjustments.NONE, null));
    }
}
