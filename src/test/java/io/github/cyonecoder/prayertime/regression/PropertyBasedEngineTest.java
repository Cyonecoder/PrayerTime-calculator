package io.github.cyonecoder.prayertime.regression;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.domain.calculation.PrayerTimeCalculator;
import io.github.cyonecoder.prayertime.domain.method.CalculationMethod;
import io.github.cyonecoder.prayertime.domain.method.CalculationParameters;
import io.github.cyonecoder.prayertime.domain.method.ElevationPolicy;
import io.github.cyonecoder.prayertime.domain.method.PrayerAdjustments;
import io.github.cyonecoder.prayertime.domain.model.Coordinates;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerRequest;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import io.github.cyonecoder.prayertime.validation.PrayerTimesValidator;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Property-style tests: for many randomized-but-valid Moroccan inputs the engine
 * must never throw, never produce out-of-order or non-normalized times, and must
 * always source its offset from Africa/Casablanca. Uses a fixed seed so failures
 * are reproducible.
 */
class PropertyBasedEngineTest {

    private static final ZoneId CASA_ZONE = ZoneId.of("Africa/Casablanca");
    private static final int ITERATIONS = 1_000;

    // Morocco (incl. Western Sahara) bounding box.
    private static final double MIN_LAT = 21.0;
    private static final double MAX_LAT = 36.0;
    private static final double MIN_LON = -17.0;
    private static final double MAX_LON = -1.0;
    private static final double MIN_ELEVATION = -50.0;
    private static final double MAX_ELEVATION = 3_000.0;

    private final PrayerTimeCalculator calculator = new PrayerTimeCalculator();
    private final PrayerTimesValidator validator = new PrayerTimesValidator();

    private static CalculationMethod moroccoMethodWith(ElevationPolicy policy) {
        CalculationParameters params = new CalculationParameters(
                19.0, 17.0, 1.0, -0.8333,
                new PrayerAdjustments(0, 0, 5, 0, 5, 0), policy);
        return new CalculationMethod() {
            @Override
            public String id() {
                return "morocco-" + policy;
            }

            @Override
            public String displayName() {
                return "Morocco (" + policy + ")";
            }

            @Override
            public CalculationParameters parameters() {
                return params;
            }
        };
    }

    @Test
    void randomizedMoroccanInputsAlwaysProduceValidOrderedTimes() {
        Random random = new Random(20260703L);

        for (int i = 0; i < ITERATIONS; i++) {
            double lat = between(random, MIN_LAT, MAX_LAT);
            double lon = between(random, MIN_LON, MAX_LON);
            double elevation = between(random, MIN_ELEVATION, MAX_ELEVATION);
            LocalDate date = LocalDate.ofYearDay(2026, 1 + random.nextInt(365));
            ElevationPolicy policy = random.nextBoolean()
                    ? ElevationPolicy.OFFICIAL_CITY_TABLE
                    : ElevationPolicy.OBSERVER_ELEVATION;

            Coordinates coordinates = new Coordinates(lat, lon, elevation);
            PrayerRequest request =
                    PrayerRequest.of(date, coordinates, CASA_ZONE, moroccoMethodWith(policy));

            String label = String.format(
                    "lat=%.3f lon=%.3f elev=%.0f date=%s policy=%s", lat, lon, elevation, date, policy);

            ZoneOffset offset = calculator.offsetFor(request);
            assertThat(offset).as("offset %s", label)
                    .isEqualTo(CASA_ZONE.getRules().getOffset(date.atTime(LocalTime.NOON)));

            PrayerTimes times = calculator.calculate(request); // must not throw

            for (Prayer prayer : Prayer.values()) {
                LocalTime t = times.get(prayer);
                assertThat(t).as("%s %s", prayer, label).isNotNull();
                assertThat(t.toSecondOfDay()).isBetween(0, 86_399);
            }

            assertThat(validator.validate(times)).as("ordering %s", label).isEmpty();
        }
    }

    private static double between(Random random, double min, double max) {
        return min + random.nextDouble() * (max - min);
    }
}
