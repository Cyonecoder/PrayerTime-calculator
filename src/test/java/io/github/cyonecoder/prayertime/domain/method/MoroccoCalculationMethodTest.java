package io.github.cyonecoder.prayertime.domain.method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.cyonecoder.prayertime.domain.model.Prayer;
import org.junit.jupiter.api.Test;

class MoroccoCalculationMethodTest {

    private final MoroccoCalculationMethod method = new MoroccoCalculationMethod();
    private final CalculationParameters params = method.parameters();

    @Test
    void hasStableIdentityAndName() {
        assertThat(method.id()).isEqualTo("morocco");
        assertThat(method.displayName()).isEqualTo("Morocco (Habous)");
        assertThat(method.methodName()).isEqualTo("Morocco (Habous)");
    }

    @Test
    void fajrAngleIs19Degrees() {
        assertThat(params.fajrAngle()).isCloseTo(19.0, within(1e-9));
    }

    @Test
    void ishaAngleIs17Degrees() {
        assertThat(params.ishaAngle()).isCloseTo(17.0, within(1e-9));
    }

    @Test
    void asrShadowFactorIsOne() {
        assertThat(params.asrShadowFactor()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void sunriseBaseAngleIsMinus0_8333() {
        assertThat(params.sunriseAngle()).isCloseTo(-0.8333, within(1e-9));
    }

    @Test
    void dhuhrAndMaghribHaveFiveMinuteBufferAndOthersZero() {
        PrayerAdjustments adj = params.adjustments();
        assertThat(adj.dhuhr()).isEqualTo(5);
        assertThat(adj.maghrib()).isEqualTo(5);
        assertThat(adj.fajr()).isZero();
        assertThat(adj.sunrise()).isZero();
        assertThat(adj.asr()).isZero();
        assertThat(adj.isha()).isZero();
    }

    @Test
    void defaultElevationPolicyIsOfficialCityTable() {
        assertThat(params.elevationPolicy()).isEqualTo(ElevationPolicy.OFFICIAL_CITY_TABLE);
    }

    @Test
    void adjustmentsLookupMatchesFields() {
        PrayerAdjustments adj = params.adjustments();
        assertThat(adj.minutesFor(Prayer.DHUHR)).isEqualTo(5);
        assertThat(adj.minutesFor(Prayer.MAGHRIB)).isEqualTo(5);
        assertThat(adj.minutesFor(Prayer.FAJR)).isZero();
    }

    @Test
    void parametersAndAdjustmentsAreImmutableRecords() {
        assertThat(CalculationParameters.class.isRecord()).isTrue();
        assertThat(PrayerAdjustments.class.isRecord()).isTrue();
    }
}
