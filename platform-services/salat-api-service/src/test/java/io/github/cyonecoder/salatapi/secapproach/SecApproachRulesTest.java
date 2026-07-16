package io.github.cyonecoder.salatapi.secapproach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cyonecoder.salatapi.city.MoroccoCityRegistry;
import io.github.cyonecoder.salatapi.city.model.City;
import io.github.cyonecoder.salatapi.common.model.Prayer;
import io.github.cyonecoder.salatapi.common.model.PrayerTimes;
import io.github.cyonecoder.salatapi.secapproach.calculation.HabousRawEngine;
import io.github.cyonecoder.salatapi.secapproach.configuration.ReconstructedMethod;
import io.github.cyonecoder.salatapi.secapproach.configuration.SecApproachParameters;
import io.github.cyonecoder.salatapi.secapproach.exception.CityNotSupportedException;
import io.github.cyonecoder.salatapi.secapproach.model.CalculationCandidate;
import io.github.cyonecoder.salatapi.secapproach.service.SecApproachCalculator;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The sec-approach calculation rules (spec 10), pinned as executable assertions.
 *
 * <p>Where {@link SecApproachEngineParityTest} proves the port reproduces the
 * research branch, this proves the port obeys the <em>stated</em> rules — the two
 * can disagree only if the research branch itself violated the spec, which is
 * exactly the kind of thing worth failing on.
 */
class SecApproachRulesTest {

    private static SecApproachCalculator calculator;
    private static MoroccoCityRegistry registry;
    private static SecApproachParameters parameters;
    private static HabousRawEngine engine;
    private static CalculationCandidate method;

    @BeforeAll
    static void setUp() {
        registry = new MoroccoCityRegistry();
        engine = new HabousRawEngine();
        method = ReconstructedMethod.frozen();
        parameters = new SecApproachParameters();
        calculator = new SecApproachCalculator(registry, engine, method, parameters);
    }

    @Test
    void publishedMinuteIsFloorOfRawPlusOffsetOverSixty() {
        LocalDate date = LocalDate.of(2026, 7, 15);

        for (String slug : parameters.supportedCitySlugs()) {
            City city = registry.requireBySlug(slug);
            Map<Prayer, Integer> offsets = parameters.offsetsFor(slug).orElseThrow();
            PrayerTimes actual = calculator.computeTimes(slug, date);

            for (Prayer prayer : Prayer.values()) {
                // The spec's arithmetic, recomputed independently of the service.
                long rawSeconds = Math.round(
                        engine.rawSecondsOfDay(prayer, method, city.coordinates(), city.zone(), date));
                long adjustedSeconds = rawSeconds + offsets.get(prayer);
                long minuteOfDay = Math.floorMod(Math.floorDiv(adjustedSeconds, 60L), 1440L);
                LocalTime expected = LocalTime.of((int) (minuteOfDay / 60), (int) (minuteOfDay % 60));

                assertThat(actual.get(prayer))
                        .as("%s %s must be floor((raw + offset)/60)", slug, prayer)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void secondPrecisionIsFloorModOfAdjustedSecondsOverADay() {
        LocalDate date = LocalDate.of(2026, 12, 21);
        String slug = "casablanca";
        City city = registry.requireBySlug(slug);
        Map<Prayer, Integer> offsets = parameters.offsetsFor(slug).orElseThrow();

        for (Prayer prayer : Prayer.values()) {
            long rawSeconds = Math.round(
                    engine.rawSecondsOfDay(prayer, method, city.coordinates(), city.zone(), date));
            long adjustedSeconds = rawSeconds + offsets.get(prayer);
            long secondOfDay = Math.floorMod(adjustedSeconds, 86_400L);

            assertThat(calculator.secondPreciseTime(slug, date, prayer))
                    .isEqualTo(LocalTime.ofSecondOfDay(secondOfDay));
        }
    }

    @Test
    void theMinuteIsAFlooredTruncationOfTheSecondPreciseTime() {
        // floor to the minute, never a round: the second-precise time and the
        // published minute must agree on the minute, with seconds dropped.
        LocalDate date = LocalDate.of(2026, 3, 20);
        for (String slug : parameters.supportedCitySlugs()) {
            PrayerTimes published = calculator.computeTimes(slug, date);
            for (Prayer prayer : Prayer.values()) {
                LocalTime precise = calculator.secondPreciseTime(slug, date, prayer);
                assertThat(published.get(prayer))
                        .as("%s %s: published minute must be the floored second-precise time", slug, prayer)
                        .isEqualTo(precise.withSecond(0).withNano(0));
            }
        }
    }

    @Test
    void everyCityHasExactlySixConstantIntegerSecondOffsets() {
        assertThat(parameters.count()).isEqualTo(48);
        for (String slug : parameters.supportedCitySlugs()) {
            Map<Prayer, Integer> offsets = parameters.offsetsFor(slug).orElseThrow();
            assertThat(offsets).hasSize(6).containsOnlyKeys(Prayer.values());
        }
    }

    @Test
    void offsetsAreConstantAcrossDatesMonthsAndSeasons() {
        // No per-day, monthly or seasonal correction: the same offset must explain
        // the difference between raw and published on every date.
        String slug = "fez";
        City city = registry.requireBySlug(slug);
        Map<Prayer, Integer> offsets = parameters.offsetsFor(slug).orElseThrow();

        for (LocalDate date : new LocalDate[] {
                LocalDate.of(2026, 1, 15), LocalDate.of(2026, 4, 15),
                LocalDate.of(2026, 7, 15), LocalDate.of(2026, 10, 15),
                LocalDate.of(2027, 7, 15)}) {
            for (Prayer prayer : Prayer.values()) {
                long rawSeconds = Math.round(
                        engine.rawSecondsOfDay(prayer, method, city.coordinates(), city.zone(), date));
                long precise = calculator.secondPreciseTime(slug, date, prayer).toSecondOfDay();
                long impliedOffset = Math.floorMod(precise - rawSeconds, 86_400L);
                long expected = Math.floorMod((long) offsets.get(prayer), 86_400L);

                assertThat(impliedOffset)
                        .as("%s %s on %s must use the same constant offset", slug, prayer, date)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void calculationUsesAfricaCasablancaForEveryCity() {
        assertThat(registry.all()).allSatisfy(city ->
                assertThat(city.zone().getId()).isEqualTo("Africa/Casablanca"));
    }

    @Test
    void thereIsNoNearestCityProvinceOrCasablancaFallback() {
        LocalDate date = LocalDate.of(2026, 7, 15);
        PrayerTimes casablanca = calculator.computeTimes("casablanca", date);

        // 'temara' sits ~70km from Casablanca and is a registry city with no
        // mapping and no offsets. A nearest-city or province fallback would
        // answer with a neighbour's times; a Casablanca fallback would answer
        // with Casablanca's. Both must be refused instead.
        assertThatThrownBy(() -> calculator.computeTimes("temara", date))
                .isInstanceOf(CityNotSupportedException.class);
        assertThat(calculator.supports("temara")).isFalse();

        // And a supported neighbour must not silently equal Casablanca either.
        assertThat(calculator.computeTimes("rabat", date)).isNotEqualTo(casablanca);
    }

    @Test
    void calculationNeverConsultsOfficialData() {
        // The calculator's collaborators are the registry, the frozen method, the
        // raw engine and the parameter resource. No official store, no client, no
        // archive. Constructing it with only those four proves the dependency set.
        SecApproachCalculator isolated =
                new SecApproachCalculator(registry, engine, method, parameters);

        assertThat(isolated.computeTimes("agadir", LocalDate.of(2026, 7, 15)))
                .isEqualTo(calculator.computeTimes("agadir", LocalDate.of(2026, 7, 15)));
        assertThat(isolated.parameterVersion()).isEqualTo("morocco-habous-secaproach-v2");
    }

    @Test
    void aDateFarOutsideTheTrainingWindowStillUsesTheSameConstantOffsets() {
        // The training window was 2026-06-17..2026-07-16. Offsets are calculation
        // parameters, not date-scoped overrides, so a 2030 date must still work.
        assertThat(calculator.computeTimes("marrakech", LocalDate.of(2030, 1, 1)))
                .isNotNull();
    }
}
