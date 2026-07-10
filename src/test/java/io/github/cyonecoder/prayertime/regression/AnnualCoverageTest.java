package io.github.cyonecoder.prayertime.regression;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.domain.calculation.PrayerTimeCalculator;
import io.github.cyonecoder.prayertime.domain.method.MoroccoCalculationMethod;
import io.github.cyonecoder.prayertime.domain.model.City;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerRequest;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry;
import io.github.cyonecoder.prayertime.validation.PrayerTimesValidator;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercises the engine for every day of 2026 across the major Moroccan cities,
 * asserting the invariants that must hold on every single day.
 */
class AnnualCoverageTest {

    private static final ZoneId CASA_ZONE = ZoneId.of("Africa/Casablanca");
    private static final int SECONDS_PER_DAY = 86_400;
    /** Same-offset day-to-day movement is a few minutes; allow generous slack. */
    private static final int MAX_SAME_OFFSET_DELTA_MIN = 15;
    /** Across a civil-clock transition the shift is ~60 min (one hour). */
    private static final int MIN_TRANSITION_DELTA_MIN = 40;
    private static final int MAX_TRANSITION_DELTA_MIN = 80;

    private final MoroccoCityRegistry registry = new MoroccoCityRegistry();
    private final PrayerTimeCalculator calculator = new PrayerTimeCalculator();
    private final PrayerTimesValidator validator = new PrayerTimesValidator();

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "rabat", "casablanca", "fez", "marrakech", "tangier", "agadir", "oujda", "laayoune"})
    void everyDayOf2026IsValid(String cityId) {
        City city = registry.requireById(cityId);
        PrayerTimes previous = null;
        ZoneOffset previousOffset = null;

        for (LocalDate date = LocalDate.of(2026, 1, 1);
             !date.isAfter(LocalDate.of(2026, 12, 31));
             date = date.plusDays(1)) {

            PrayerRequest request =
                    PrayerRequest.of(date, city.coordinates(), CASA_ZONE, new MoroccoCalculationMethod());

            // 10: the offset comes from Africa/Casablanca.
            ZoneOffset offset = calculator.offsetFor(request);
            assertThat(offset)
                    .as("offset for %s on %s", cityId, date)
                    .isEqualTo(CASA_ZONE.getRules().getOffset(date.atTime(LocalTime.NOON)));

            // 1: no exception (implicit — this line throws on failure).
            PrayerTimes times = calculator.calculate(request);

            // 2 & 3: all times are real, normalized clock times (LocalTime is inherently 0..86399s).
            for (Prayer prayer : Prayer.values()) {
                LocalTime t = times.get(prayer);
                assertThat(t).as("%s %s on %s", cityId, prayer, date).isNotNull();
                assertThat(t.toSecondOfDay()).isBetween(0, SECONDS_PER_DAY - 1);
            }

            // 4-8: chronological ordering (validator returns no issues).
            assertThat(validator.validate(times))
                    .as("ordering for %s on %s", cityId, date)
                    .isEmpty();

            // 9: day-to-day movement is plausible (small within an offset regime,
            //    ~1 hour across a civil-clock transition, never ~2 hours).
            if (previous != null) {
                boolean offsetChanged = !offset.equals(previousOffset);
                for (Prayer prayer : Prayer.values()) {
                    long delta = Math.abs(minuteOfDay(times.get(prayer)) - minuteOfDay(previous.get(prayer)));
                    if (offsetChanged) {
                        assertThat(delta)
                                .as("transition delta for %s %s on %s", cityId, prayer, date)
                                .isBetween((long) MIN_TRANSITION_DELTA_MIN, (long) MAX_TRANSITION_DELTA_MIN);
                    } else {
                        assertThat(delta)
                                .as("daily delta for %s %s on %s", cityId, prayer, date)
                                .isLessThanOrEqualTo(MAX_SAME_OFFSET_DELTA_MIN);
                    }
                }
            }

            previous = times;
            previousOffset = offset;
        }
    }

    private static long minuteOfDay(LocalTime time) {
        return time.toSecondOfDay() / 60L;
    }
}
