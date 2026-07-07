package io.github.cyonecoder.prayertime.audit.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.domain.calculation.PrayerTimeCalculator;
import io.github.cyonecoder.prayertime.domain.method.CalculationParameters;
import io.github.cyonecoder.prayertime.domain.method.ElevationPolicy;
import io.github.cyonecoder.prayertime.domain.method.MoroccoCalculationMethod;
import io.github.cyonecoder.prayertime.domain.model.City;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerRequest;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Guards the completion criterion "current production output remains unchanged":
 * running the audit search must not perturb the production Morocco method
 * defaults or the production calculator's output.
 */
class ProductionUnchangedTest {

    private static final Path DATASET = Path.of("research-data/habous");
    private static final LocalDate DATE = LocalDate.of(2026, 7, 3);

    private static City casablanca() {
        return new MoroccoCityRegistry().requireById("casablanca");
    }

    private static int productionSunriseMinute() {
        City city = casablanca();
        PrayerRequest request = PrayerRequest.of(
                DATE, city.coordinates(), city.zone(), new MoroccoCalculationMethod());
        PrayerTimes times = new PrayerTimeCalculator().calculate(request);
        LocalTime sunrise = times.sunrise();
        return sunrise.getHour() * 60 + sunrise.getMinute();
    }

    @Test
    void moroccoMethodDefaultsAreUnchangedAfterRunningTheSearch() {
        CalculationParameters before = new MoroccoCalculationMethod().parameters();

        // Run a full audit; it must not mutate any shared/static production state.
        ReferenceDataset dataset = ReferenceDataset.load(DATASET)
                .filter(Set.of("casablanca"), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        ParameterSearchService service =
                new ParameterSearchService(new HabousRawEngine(), new CityLocations());
        new PrayerParameterSearch(service).searchAll(
                dataset, RoundingPolicy.NEAREST_MINUTE, OperationOrder.RAW_THEN_ADJUST_THEN_ROUND);

        CalculationParameters after = new MoroccoCalculationMethod().parameters();
        assertThat(after.fajrAngle()).isEqualTo(before.fajrAngle()).isEqualTo(19.0);
        assertThat(after.ishaAngle()).isEqualTo(before.ishaAngle()).isEqualTo(17.0);
        assertThat(after.asrShadowFactor()).isEqualTo(before.asrShadowFactor()).isEqualTo(1.0);
        assertThat(after.sunriseAngle()).isEqualTo(before.sunriseAngle()).isEqualTo(-0.8333);
        assertThat(after.adjustments().dhuhr()).isEqualTo(5);
        assertThat(after.adjustments().maghrib()).isEqualTo(5);
        assertThat(after.elevationPolicy()).isEqualTo(ElevationPolicy.OFFICIAL_CITY_TABLE);
    }

    @Test
    void productionCalculatorOutputIsIdenticalBeforeAndAfterASearch() {
        int before = productionSunriseMinute();
        new PrayerParameterSearch(
                new ParameterSearchService(new HabousRawEngine(), new CityLocations()))
                .searchPrayer(Prayer.SUNRISE,
                        ReferenceDataset.load(DATASET).filter(Set.of("casablanca"), null, null),
                        RoundingPolicy.NEAREST_MINUTE, OperationOrder.RAW_THEN_ADJUST_THEN_ROUND);
        int after = productionSunriseMinute();
        assertThat(after).isEqualTo(before);
    }

    @Test
    void auditEngineBaselineFaithfullyReproducesProductionSunrise() {
        // The audit engine reuses the production astronomy: with the production
        // base horizon angle (-0.8333) and no adjustment, its published sunrise
        // must equal the production calculator's sunrise for the same city/date.
        City city = casablanca();
        CalculationCandidate baseline = CalculationCandidate.builder().build();
        int auditMinute = new HabousRawEngine().publishedMinuteOfDay(
                Prayer.SUNRISE, baseline, city.coordinates(), city.zone(), DATE);
        assertThat(auditMinute).isEqualTo(productionSunriseMinute());
    }
}
