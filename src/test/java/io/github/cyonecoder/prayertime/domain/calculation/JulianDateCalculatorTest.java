package io.github.cyonecoder.prayertime.domain.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class JulianDateCalculatorTest {

    private static final double TOLERANCE = 1e-6;
    private final JulianDateCalculator calculator = new JulianDateCalculator();

    private double jdAtMidnightUtc(LocalDate date) {
        return calculator.calculate(date, LocalTime.MIDNIGHT, ZoneOffset.UTC);
    }

    // --- Known reference values (JD at 00:00 UTC ends in .5) ---------------
    // Anchored on the canonical J2000.0 epoch and cross-checked by day count:
    //   2000-01-01 12:00 UTC = 2451545.0  (canonical)
    //   2026-01-01 00:00 UTC = 2461041.5  (Meeus algorithm; day-count verified)

    @Test
    void canonicalJ2000Epoch() {
        double jd = calculator.calculate(LocalDate.of(2000, 1, 1), LocalTime.NOON, ZoneOffset.UTC);
        assertThat(jd).isCloseTo(2451545.0, within(TOLERANCE));
    }

    @Test
    void january1_2026() {
        assertThat(jdAtMidnightUtc(LocalDate.of(2026, 1, 1))).isCloseTo(2461041.5, within(TOLERANCE));
    }

    @Test
    void january31_2026() {
        // +30 days after 2026-01-01
        assertThat(jdAtMidnightUtc(LocalDate.of(2026, 1, 31))).isCloseTo(2461071.5, within(TOLERANCE));
    }

    @Test
    void february1_2026() {
        // +31 days: exercises the Jan/Feb "previous year" correction branch
        assertThat(jdAtMidnightUtc(LocalDate.of(2026, 2, 1))).isCloseTo(2461072.5, within(TOLERANCE));
    }

    @Test
    void february28_2026() {
        // +58 days
        assertThat(jdAtMidnightUtc(LocalDate.of(2026, 2, 28))).isCloseTo(2461099.5, within(TOLERANCE));
    }

    @Test
    void march1_2026() {
        // +59 days (2026 is not a leap year)
        assertThat(jdAtMidnightUtc(LocalDate.of(2026, 3, 1))).isCloseTo(2461100.5, within(TOLERANCE));
    }

    @Test
    void july3_2026() {
        // day-of-year 184 -> +183 days after 2026-01-01
        assertThat(jdAtMidnightUtc(LocalDate.of(2026, 7, 3))).isCloseTo(2461224.5, within(TOLERANCE));
    }

    @Test
    void leapDayFebruary29_2024() {
        // 2024 is a leap year; the day exists and lands on JD 2460369.5.
        assertThat(jdAtMidnightUtc(LocalDate.of(2024, 2, 29))).isCloseTo(2460369.5, within(TOLERANCE));
    }

    @Test
    void leapYearFebruaryHas29Days() {
        // Feb 28 + 2 days == Mar 1 in a leap year, proving the corrected
        // Jan/Feb branch keeps the calendar continuous across the leap day.
        double feb28 = jdAtMidnightUtc(LocalDate.of(2024, 2, 28));
        double feb29 = jdAtMidnightUtc(LocalDate.of(2024, 2, 29));
        double mar1 = jdAtMidnightUtc(LocalDate.of(2024, 3, 1));
        assertThat(feb29 - feb28).isCloseTo(1.0, within(TOLERANCE));
        assertThat(mar1 - feb28).isCloseTo(2.0, within(TOLERANCE));
    }

    @Test
    void fractionalDayFromReferenceTimeIsIncluded() {
        double midnight = calculator.calculate(LocalDate.of(2026, 7, 3), LocalTime.MIDNIGHT, ZoneOffset.UTC);
        double noon = calculator.calculate(LocalDate.of(2026, 7, 3), LocalTime.NOON, ZoneOffset.UTC);
        assertThat(noon - midnight).isCloseTo(0.5, within(TOLERANCE));
    }

    @Test
    void sameInstantDifferentOffsetsYieldSameJulianDate() {
        // 14:00 at +01:00 is the same instant as 13:00 at UTC.
        double atPlusOne =
                calculator.calculate(LocalDate.of(2026, 7, 3), LocalTime.of(14, 0), ZoneOffset.ofHours(1));
        double atUtc =
                calculator.calculate(LocalDate.of(2026, 7, 3), LocalTime.of(13, 0), ZoneOffset.UTC);
        assertThat(atPlusOne).isCloseTo(atUtc, within(TOLERANCE));
    }

    @Test
    void offsetSubtractionStaysContinuousAcrossMidnight() {
        // 00:30 at +01:00 is the previous day 23:30 UTC -> identical JD.
        double afterMidnightLocal =
                calculator.calculate(LocalDate.of(2026, 7, 3), LocalTime.of(0, 30), ZoneOffset.ofHours(1));
        double lateNightUtc =
                calculator.calculate(LocalDate.of(2026, 7, 2), LocalTime.of(23, 30), ZoneOffset.UTC);
        assertThat(afterMidnightLocal).isCloseTo(lateNightUtc, within(TOLERANCE));
    }

    @Test
    void rejectsNullArguments() {
        assertThatNullPointerException()
                .isThrownBy(() -> calculator.calculate(null, LocalTime.NOON, ZoneOffset.UTC));
        assertThatNullPointerException()
                .isThrownBy(() -> calculator.calculate(LocalDate.of(2026, 1, 1), null, ZoneOffset.UTC));
        assertThatNullPointerException()
                .isThrownBy(() -> calculator.calculate(LocalDate.of(2026, 1, 1), LocalTime.NOON, null));
    }
}
