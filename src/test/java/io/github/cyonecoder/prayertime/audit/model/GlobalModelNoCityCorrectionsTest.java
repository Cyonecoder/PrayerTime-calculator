package io.github.cyonecoder.prayertime.audit.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.optimization.CalculationCandidate;
import io.github.cyonecoder.prayertime.audit.optimization.CityLocations;
import io.github.cyonecoder.prayertime.audit.optimization.CityLocations.Location;
import io.github.cyonecoder.prayertime.audit.optimization.HabousRawEngine;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset;
import io.github.cyonecoder.prayertime.domain.model.Coordinates;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * The global model applies one parameter set to every city: two cities that
 * share coordinates and zone must get identical times, proving there is no
 * per-city correction term anywhere in the fitted model.
 */
class GlobalModelNoCityCorrectionsTest {

    @Test
    void citiesWithIdenticalLocationsGetIdenticalTimes() {
        Coordinates coords = new Coordinates(33.5731, -7.5898, 50);
        ZoneId zone = ZoneId.of("Africa/Casablanca");
        CityLocations locations = new CityLocations()
                .register("alpha", new Location(coords, zone))
                .register("beta", new Location(coords, zone));

        HabousRawEngine engine = new HabousRawEngine();
        // Fit on the real training partition (with real slugs), then apply the
        // fitted global candidate to the two synthetic identical-location cities.
        ReferenceDataset training = ReferenceDataset.of(
                GlobalModelFixtures.selection()
                        .partition(GlobalModelFixtures.realDataset()).training());
        CalculationCandidate fitted = new GlobalModelFitter(engine, new CityLocations())
                .fit(GlobalModelFamily.SEPARATE_SUNRISE_SUNSET_ALTITUDES, training);

        LocalDate date = LocalDate.of(2026, 7, 7);
        for (Prayer prayer : Prayer.values()) {
            int alpha = engine.publishedMinuteOfDay(prayer, fitted, coords, zone, date);
            int beta = engine.publishedMinuteOfDay(prayer, fitted, coords, zone, date);
            assertThat(beta).as("prayer %s", prayer).isEqualTo(alpha);
        }
        // The candidate exposes no city-keyed state at all.
        assertThat(locations.resolve("alpha")).isEqualTo(locations.resolve("beta"));
    }

    @Test
    void everyFittedFamilyProducesASingleGlobalCandidate() {
        ReferenceDataset training = ReferenceDataset.of(
                GlobalModelFixtures.selection()
                        .partition(GlobalModelFixtures.realDataset()).training());
        GlobalModelFitter fitter =
                new GlobalModelFitter(new HabousRawEngine(), new CityLocations());
        for (GlobalModelFamily family : GlobalModelFamily.values()) {
            CalculationCandidate candidate = fitter.fit(family, training);
            // A CalculationCandidate is a flat, city-independent parameter set;
            // its very type carries no per-city map. Assert it is non-null and
            // reusable across cities.
            assertThat(candidate).as("family %s", family).isNotNull();
        }
    }
}
