package io.github.cyonecoder.prayertime.domain.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SolarPositionCalculatorTest {

    private final JulianDateCalculator julian = new JulianDateCalculator();
    private final SolarPositionCalculator calculator = new SolarPositionCalculator();

    // Legacy input: Casablanca, 2024-03-17 23:59:59 UTC, longitude -6.7480954.
    private static final double CASA_LONGITUDE = -6.7480954;

    @Test
    void reproducesLegacySolarValuesForOriginalDateAndLocation() {
        double jd = julian.calculate(
                LocalDate.of(2024, 3, 17), LocalTime.of(23, 59, 59), ZoneOffset.UTC);

        SolarPosition solar = calculator.calculate(jd, CASA_LONGITUDE, ZoneOffset.UTC);

        // Values reproduced from the legacy formulas (DELTA, ET, TT).
        assertThat(solar.declinationDegrees()).isCloseTo(-0.835143, within(1e-4));
        assertThat(solar.equationOfTimeMinutes()).isCloseTo(-8.026593, within(1e-4));
        assertThat(solar.solarNoonDecimalHours()).isCloseTo(12.583650, within(1e-4));
    }

    @Test
    void legacySolarNoonPlusDhuhrBufferMatchesLegacyPrintout() {
        // TT (12.583650h = 12:35:01) + 6 min == legacy Dhuhr 12:41:01.
        double jd = julian.calculate(
                LocalDate.of(2024, 3, 17), LocalTime.of(23, 59, 59), ZoneOffset.UTC);
        double ttSeconds = calculator.calculate(jd, CASA_LONGITUDE, ZoneOffset.UTC)
                .solarNoonDecimalHours() * 3600.0;
        long dhuhrSeconds = Math.round(ttSeconds) + 6 * 60;
        assertThat(LocalTime.ofSecondOfDay(dhuhrSeconds)).isEqualTo(LocalTime.of(12, 41, 1));
    }

    @Test
    void rabatJuly3_2026_hasRealisticSolarNoon() {
        // Rabat ~ -6.8416°; civil offset +01:00 on 2026-07-03.
        double jd = julian.calculate(
                LocalDate.of(2026, 7, 3), LocalTime.NOON, ZoneOffset.ofHours(1));
        SolarPosition solar = calculator.calculate(jd, -6.8416, ZoneOffset.ofHours(1));

        // Solar noon should land in the early afternoon on UTC+1 civil time.
        assertThat(solar.solarNoonDecimalHours()).isBetween(13.0, 14.0);
        assertThat(solar.declinationDegrees()).isBetween(22.0, 24.0); // near summer solstice
    }

    @Test
    void increasingLongitudeMovesSolarNoonEarlier() {
        double jd = julian.calculate(LocalDate.of(2026, 7, 3), LocalTime.NOON, ZoneOffset.ofHours(1));

        double west = calculator.calculate(jd, -8.0, ZoneOffset.ofHours(1)).solarNoonDecimalHours();
        double east = calculator.calculate(jd, -5.0, ZoneOffset.ofHours(1)).solarNoonDecimalHours();

        // More easterly (larger) longitude => earlier (smaller) solar noon.
        assertThat(east).isLessThan(west);
        // Exactly 3 degrees / 15 = 0.2 h apart.
        assertThat(west - east).isCloseTo(0.2, within(1e-9));
    }

    @Test
    void increasingOffsetShiftsLocalSolarNoonLater() {
        double jd = julian.calculate(LocalDate.of(2026, 7, 3), LocalTime.NOON, ZoneOffset.UTC);

        double atUtc = calculator.calculate(jd, CASA_LONGITUDE, ZoneOffset.UTC).solarNoonDecimalHours();
        double atPlusOne =
                calculator.calculate(jd, CASA_LONGITUDE, ZoneOffset.ofHours(1)).solarNoonDecimalHours();

        assertThat(atPlusOne - atUtc).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void producesFiniteValuesForMajorMoroccanCoordinates() {
        // (name, latitude unused here, longitude)
        double[] longitudes = {-6.8416, -7.5898, -7.9811, -5.0003, -1.9086, -9.5981, -8.0083};
        double jd = julian.calculate(LocalDate.of(2026, 7, 3), LocalTime.NOON, ZoneOffset.ofHours(1));
        for (double lon : longitudes) {
            SolarPosition solar = calculator.calculate(jd, lon, ZoneOffset.ofHours(1));
            assertThat(Double.isFinite(solar.declinationDegrees())).isTrue();
            assertThat(Double.isFinite(solar.equationOfTimeMinutes())).isTrue();
            assertThat(Double.isFinite(solar.solarNoonDecimalHours())).isTrue();
        }
    }

    @Test
    void rejectsNonFiniteInputsAndNullOffset() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> calculator.calculate(Double.NaN, CASA_LONGITUDE, ZoneOffset.UTC));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> calculator.calculate(2461224.5, Double.POSITIVE_INFINITY, ZoneOffset.UTC));
        assertThatNullPointerException()
                .isThrownBy(() -> calculator.calculate(2461224.5, CASA_LONGITUDE, null));
    }
}
