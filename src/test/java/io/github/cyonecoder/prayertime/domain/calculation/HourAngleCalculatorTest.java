package io.github.cyonecoder.prayertime.domain.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.within;

import io.github.cyonecoder.prayertime.domain.calculation.HourAngleCalculator.Direction;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class HourAngleCalculatorTest {

    private static final double FAJR_ALT = -19.0;
    private static final double ISHA_ALT = -17.0;
    private static final double SUNRISE_ALT = -0.8333;
    private static final ZoneOffset PLUS_ONE = ZoneOffset.ofHours(1);

    private final HourAngleCalculator calculator = new HourAngleCalculator();
    private final JulianDateCalculator julian = new JulianDateCalculator();
    private final SolarPositionCalculator solar = new SolarPositionCalculator();
    private final TimeConversion conversion = new TimeConversion();

    @Test
    void cosineSlightlyAboveOneIsClampedToZeroDegrees() {
        double angle = calculator.hourAngleFromCosineDegrees(1.0 + 1e-9);
        assertThat(angle).isCloseTo(0.0, within(1e-6));
        assertThat(Double.isNaN(angle)).isFalse();
    }

    @Test
    void cosineSlightlyBelowMinusOneIsClampedTo180Degrees() {
        double angle = calculator.hourAngleFromCosineDegrees(-1.0 - 1e-9);
        assertThat(angle).isCloseTo(180.0, within(1e-6));
        assertThat(Double.isNaN(angle)).isFalse();
    }

    @Test
    void clearlyImpossibleCosineThrowsInsteadOfReturningNaN() {
        assertThatExceptionOfType(HourAngleCalculator.EventDoesNotOccurException.class)
                .isThrownBy(() -> calculator.hourAngleFromCosineDegrees(1.5));
        // An unreachable altitude at a real location does not "occur".
        assertThat(calculator.eventOccurs(89.0, 34.0, 23.0)).isFalse();
    }

    @Test
    void morningOffsetIsNegativeAndEveningPositive() {
        double morning = calculator.hourOffsetHours(Direction.MORNING, FAJR_ALT, 33.57, 23.0);
        double evening = calculator.hourOffsetHours(Direction.EVENING, FAJR_ALT, 33.57, 23.0);
        assertThat(morning).isNegative();
        assertThat(evening).isPositive();
        assertThat(morning).isCloseTo(-evening, within(1e-12));
    }

    @Test
    void asrFactor1AndFactor2ProduceDifferentTimes() {
        double lat = 33.5731;
        double jd = julian.calculate(LocalDate.of(2026, 7, 3), LocalTime.NOON, PLUS_ONE);
        SolarPosition position = solar.calculate(jd, -7.5898, PLUS_ONE);
        double decl = position.declinationDegrees();
        double noon = position.solarNoonDecimalHours();

        double asrAlt1 = calculator.asrAltitudeDegrees(1.0, lat, decl);
        double asrAlt2 = calculator.asrAltitudeDegrees(2.0, lat, decl);
        assertThat(asrAlt1).isNotEqualTo(asrAlt2);

        LocalTime asr1 = conversion.toLocalTime(
                noon + calculator.hourOffsetHours(Direction.EVENING, asrAlt1, lat, decl));
        LocalTime asr2 = conversion.toLocalTime(
                noon + calculator.hourOffsetHours(Direction.EVENING, asrAlt2, lat, decl));
        // Hanafi (factor 2) is always later than the majority (factor 1).
        assertThat(asr2).isAfter(asr1);
    }

    @Test
    void producesFiniteTimesForAllMajorMoroccanCitiesOnJuly3_2026() {
        // name, latitude, longitude
        double[][] cities = {
                {33.5731, -7.5898},  // Casablanca
                {34.0209, -6.8416},  // Rabat
                {31.6295, -7.9811},  // Marrakesh
                {34.0181, -5.0078},  // Fes
                {35.7595, -5.8340},  // Tangier
                {30.4278, -9.5981},  // Agadir
                {34.6867, -1.9114},  // Oujda
                {27.1536, -13.2033}, // Laayoune
                {23.6848, -15.9570}, // Dakhla
        };
        double jd = julian.calculate(LocalDate.of(2026, 7, 3), LocalTime.NOON, PLUS_ONE);

        for (double[] city : cities) {
            double lat = city[0];
            double lon = city[1];
            SolarPosition position = solar.calculate(jd, lon, PLUS_ONE);
            double decl = position.declinationDegrees();
            double noon = position.solarNoonDecimalHours();
            double asrAlt = calculator.asrAltitudeDegrees(1.0, lat, decl);

            double fajr = noon + calculator.hourOffsetHours(Direction.MORNING, FAJR_ALT, lat, decl);
            double sunrise = noon + calculator.hourOffsetHours(Direction.MORNING, SUNRISE_ALT, lat, decl);
            double asr = noon + calculator.hourOffsetHours(Direction.EVENING, asrAlt, lat, decl);
            double maghrib = noon + calculator.hourOffsetHours(Direction.EVENING, SUNRISE_ALT, lat, decl);
            double isha = noon + calculator.hourOffsetHours(Direction.EVENING, ISHA_ALT, lat, decl);

            for (double h : new double[] {fajr, sunrise, asr, maghrib, isha}) {
                assertThat(Double.isFinite(h)).isTrue();
                assertThat(conversion.toLocalTime(h)).isNotNull();
            }
        }
    }
}
