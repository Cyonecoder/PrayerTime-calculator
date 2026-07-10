package io.github.cyonecoder.prayertime.regression;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.domain.calculation.PrayerTimeCalculator;
import io.github.cyonecoder.prayertime.domain.method.MoroccoCalculationMethod;
import io.github.cyonecoder.prayertime.domain.model.Coordinates;
import io.github.cyonecoder.prayertime.domain.model.PrayerRequest;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import io.github.cyonecoder.prayertime.validation.PrayerTimesValidator;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Focused checks on calendar edge cases and the Moroccan clock-transition
 * boundaries, using Casablanca (2024 leap day included).
 */
class FocusedDatesTest {

    private static final ZoneId CASA_ZONE = ZoneId.of("Africa/Casablanca");
    private static final Coordinates CASABLANCA = new Coordinates(33.5731, -7.5898, 0);
    private static final ZoneOffset PLUS_ONE = ZoneOffset.ofHours(1);
    private static final ZoneOffset ZERO = ZoneOffset.ofHours(0);

    private final PrayerTimeCalculator calculator = new PrayerTimeCalculator();
    private final PrayerTimesValidator validator = new PrayerTimesValidator();

    private PrayerRequest request(LocalDate date) {
        return PrayerRequest.of(date, CASABLANCA, CASA_ZONE, new MoroccoCalculationMethod());
    }

    @Test
    void allFocusDatesComputeAndAreOrdered() {
        LocalDate[] dates = {
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 2, 14),
                LocalDate.of(2026, 2, 15), LocalDate.of(2026, 3, 21),
                LocalDate.of(2026, 3, 22), LocalDate.of(2026, 7, 3),
                LocalDate.of(2024, 2, 29),
        };
        for (LocalDate date : dates) {
            PrayerTimes times = calculator.calculate(request(date)); // no exception
            assertThat(validator.validate(times))
                    .as("ordering on %s", date)
                    .isEmpty();
        }
    }

    @Test
    void offsetsMatchAfricaCasablancaIncludingRamadanSuspension() {
        Map<LocalDate, ZoneOffset> expected = Map.ofEntries(
                Map.entry(LocalDate.of(2026, 1, 1), PLUS_ONE),
                Map.entry(LocalDate.of(2026, 1, 31), PLUS_ONE),
                Map.entry(LocalDate.of(2026, 2, 1), PLUS_ONE),
                Map.entry(LocalDate.of(2026, 2, 14), PLUS_ONE),
                Map.entry(LocalDate.of(2026, 2, 15), ZERO),   // suspension starts
                Map.entry(LocalDate.of(2026, 2, 28), ZERO),
                Map.entry(LocalDate.of(2026, 3, 1), ZERO),
                Map.entry(LocalDate.of(2026, 3, 21), ZERO),
                Map.entry(LocalDate.of(2026, 3, 22), PLUS_ONE), // suspension ends
                Map.entry(LocalDate.of(2026, 7, 3), PLUS_ONE),
                Map.entry(LocalDate.of(2024, 2, 29), PLUS_ONE));

        expected.forEach((date, offset) ->
                assertThat(calculator.offsetFor(request(date)))
                        .as("offset on %s", date)
                        .isEqualTo(offset));
    }
}
