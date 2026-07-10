package io.github.cyonecoder.prayertime.regression;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.domain.calculation.PrayerTimeCalculator;
import io.github.cyonecoder.prayertime.domain.method.MoroccoCalculationMethod;
import io.github.cyonecoder.prayertime.domain.model.Coordinates;
import io.github.cyonecoder.prayertime.domain.model.PrayerRequest;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * Verifies that civil prayer times shift by ~one hour across Morocco's
 * clock transitions, that the underlying UTC solar instant stays continuous
 * (i.e. the offset is applied exactly once), and that no ~2-hour double shift
 * or manual +60 min occurs.
 */
class ClockTransitionTest {

    private static final ZoneId CASA_ZONE = ZoneId.of("Africa/Casablanca");
    private static final Coordinates CASABLANCA = new Coordinates(33.5731, -7.5898, 0);

    private final PrayerTimeCalculator calculator = new PrayerTimeCalculator();

    private PrayerTimes times(LocalDate date) {
        return calculator.calculate(
                PrayerRequest.of(date, CASABLANCA, CASA_ZONE, new MoroccoCalculationMethod()));
    }

    private long offsetMinutes(LocalDate date) {
        return calculator.offsetFor(
                        PrayerRequest.of(date, CASABLANCA, CASA_ZONE, new MoroccoCalculationMethod()))
                .getTotalSeconds() / 60L;
    }

    private static long minuteOfDay(LocalTime t) {
        return t.toSecondOfDay() / 60L;
    }

    @Test
    void ramadanSuspensionStartShiftsCivilTimesBackByAboutOneHour() {
        // 2026-02-14 (+01:00) -> 2026-02-15 (+00:00): civil times move ~1h earlier.
        assertAboutOneCivilHourApart(LocalDate.of(2026, 2, 14), LocalDate.of(2026, 2, 15), -1);
    }

    @Test
    void ramadanSuspensionEndShiftsCivilTimesForwardByAboutOneHour() {
        // 2026-03-21 (+00:00) -> 2026-03-22 (+01:00): civil times move ~1h later.
        assertAboutOneCivilHourApart(LocalDate.of(2026, 3, 21), LocalDate.of(2026, 3, 22), +1);
    }

    private void assertAboutOneCivilHourApart(LocalDate before, LocalDate after, int direction) {
        PrayerTimes b = times(before);
        PrayerTimes a = times(after);

        long offsetDelta = offsetMinutes(after) - offsetMinutes(before);
        assertThat(offsetDelta).isEqualTo(direction * 60L);

        // Civil Dhuhr shifts by ~1 hour in the same direction (plus a couple of
        // minutes of normal astronomical drift), and NOT ~2 hours.
        long civilDelta = minuteOfDay(a.dhuhr()) - minuteOfDay(b.dhuhr());
        assertThat(civilDelta)
                .as("civil Dhuhr shift %s->%s", before, after)
                .isBetween(direction * 60L - 5, direction * 60L + 5);

        // The UTC solar instant (civil Dhuhr minus offset) is continuous: the
        // offset was applied exactly once, so there is no double shift.
        long utcBefore = minuteOfDay(b.dhuhr()) - offsetMinutes(before);
        long utcAfter = minuteOfDay(a.dhuhr()) - offsetMinutes(after);
        assertThat(Math.abs(utcAfter - utcBefore))
                .as("UTC solar-noon continuity across %s->%s", before, after)
                .isLessThanOrEqualTo(3);
    }
}
