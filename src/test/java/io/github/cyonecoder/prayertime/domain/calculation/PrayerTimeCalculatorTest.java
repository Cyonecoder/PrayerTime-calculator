package io.github.cyonecoder.prayertime.domain.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.cyonecoder.prayertime.domain.method.MoroccoCalculationMethod;
import io.github.cyonecoder.prayertime.domain.model.Coordinates;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerRequest;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class PrayerTimeCalculatorTest {

    private static final ZoneId CASA_ZONE = ZoneId.of("Africa/Casablanca");
    private static final Coordinates CASABLANCA = new Coordinates(33.5731, -7.5898, 50);

    private final PrayerTimeCalculator calculator = new PrayerTimeCalculator();

    private PrayerRequest request(LocalDate date) {
        return PrayerRequest.of(date, CASABLANCA, CASA_ZONE, new MoroccoCalculationMethod());
    }

    @Test
    void isDeterministicForTheSameRequest() {
        PrayerRequest req = request(LocalDate.of(2026, 7, 3));
        assertThat(calculator.calculate(req)).isEqualTo(calculator.calculate(req));
    }

    @Test
    void systemDefaultTimeZoneDoesNotChangeResults() {
        PrayerRequest req = request(LocalDate.of(2026, 7, 3));
        PrayerTimes baseline = calculator.calculate(req);

        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati")); // UTC+14
            assertThat(calculator.calculate(req)).isEqualTo(baseline);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void july3_2026ResolvesToUtcPlusOne() {
        assertThat(calculator.offsetFor(request(LocalDate.of(2026, 7, 3))))
                .isEqualTo(ZoneOffset.ofHours(1));
    }

    @Test
    void ramadanSuspensionDateResolvesToUtcZero() {
        // 2026-03-01 falls inside the Ramadan clock suspension.
        assertThat(calculator.offsetFor(request(LocalDate.of(2026, 3, 1))))
                .isEqualTo(ZoneOffset.ofHours(0));
    }

    @Test
    void appliesTheOffsetOnceWithNoDoubleHourShift() {
        LocalDate date = LocalDate.of(2026, 7, 3);
        PrayerTimes times = calculator.calculate(request(date));

        // Independently compute solar noon at +01:00 and add the +5 min Dhuhr buffer.
        double jd = new JulianDateCalculator().calculate(date, LocalTime.NOON, ZoneOffset.ofHours(1));
        double solarNoon = new SolarPositionCalculator()
                .calculate(jd, CASABLANCA.longitude(), ZoneOffset.ofHours(1))
                .solarNoonDecimalHours();
        LocalTime expectedDhuhr =
                new TimeConversion().toLocalTimeRoundedToMinute(solarNoon + 5.0 / 60.0);

        // If the offset were applied twice, Dhuhr would be ~60 minutes later.
        assertThat(times.dhuhr()).isEqualTo(expectedDhuhr);
        assertThat(times.dhuhr()).isBefore(LocalTime.of(14, 0));
    }

    @Test
    void januaryAndFebruaryNowProduceValidOrderedTimes() {
        for (LocalDate date : new LocalDate[] {
                LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 15)}) {
            PrayerTimes t = calculator.calculate(request(date)); // must not throw
            assertThat(t.fajr()).isBefore(t.sunrise());
            assertThat(t.sunrise()).isBefore(t.dhuhr());
            assertThat(t.dhuhr()).isBefore(t.asr());
            assertThat(t.asr()).isBefore(t.maghrib());
            assertThat(t.maghrib()).isBefore(t.isha());
            assertThat(t.dhuhr()).isBetween(LocalTime.of(11, 0), LocalTime.of(14, 0));
        }
    }

    @Test
    void correctedEngineDiffersFromArchivedLegacyValue() {
        // The legacy implementation (now removed; see docs/audit/baseline.md and
        // docs/audit/legacy-archive.md) produced Dhuhr 12:41:01 for these inputs.
        // The corrected engine is independent (Morocco method, +5 min Dhuhr buffer,
        // civil offset resolved from Africa/Casablanca) and must not reproduce it.
        String archivedLegacyDhuhr = "12:41:01";
        PrayerTimes engine = calculator.calculate(
                PrayerRequest.of(LocalDate.of(2024, 3, 17), new Coordinates(34.0361152, -6.7480954, 96),
                        CASA_ZONE, new MoroccoCalculationMethod()));
        assertThat(engine.dhuhr().toString()).isNotEqualTo(archivedLegacyDhuhr);
    }

    @Test
    void invalidOrderingThrowsWithAffectedPrayerNames() {
        // Sunrise before Fajr is out of order.
        PrayerTimes outOfOrder = new PrayerTimes(
                LocalDate.of(2026, 7, 3),
                LocalTime.of(6, 0),   // fajr
                LocalTime.of(5, 0),   // sunrise (too early)
                LocalTime.of(13, 0),
                LocalTime.of(17, 0),
                LocalTime.of(20, 0),
                LocalTime.of(21, 0));

        assertThatExceptionOfType(PrayerTimeCalculator.InvalidPrayerOrderingException.class)
                .isThrownBy(() -> PrayerTimeCalculator.validateOrdering(outOfOrder))
                .satisfies(ex -> assertThat(ex.affectedPrayers())
                        .containsExactly(Prayer.FAJR, Prayer.SUNRISE));
    }
}
