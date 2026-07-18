package io.github.cyonecoder.prayertime.application.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.optimization.HabousRawEngine;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset.Observation;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachGates;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachTrainer;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachTrainer.CityResult;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachTrainingCommand;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachTrainingDataset;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Enforces the sec-approach release accuracy gates on the committed parameters:
 * the aggregate gates over all supported cities, the per-city gates for every
 * accepted new city, and the existing-14 regression guarantees.
 */
class SecApproachReleaseGateTest {

    private static final Path DATASET = Path.of("research-data/habous");
    private static final Path BASE14 =
            Path.of("src/main/resources/research/morocco-habous-secaproach-base14.json");

    private final ReferenceDataset oracle = ReferenceDataset.load(DATASET);
    private final SecondsApproachPrayerTimesProvider sec = new SecondsApproachPrayerTimesProvider();
    private final AstronomicalPrayerTimesProvider global = new AstronomicalPrayerTimesProvider();

    private SecApproachTrainer.Outcome trained() {
        SecApproachTrainingDataset ds = SecApproachTrainingDataset.fromDirectory(
                DATASET, new MoroccoCityRegistry(), new HabousRawEngine());
        return new SecApproachTrainer(-900, 900)
                .train(ds, SecApproachTrainingCommand.loadOffsets(BASE14));
    }

    private static int minuteOfDay(LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }

    private static int circularError(int a, int b) {
        return Math.floorMod(a - b + 720, 1440) - 720;
    }

    @Test
    void aggregateGatesPassOverAllSupportedCities() {
        int secExact = 0;
        int secWithin1 = 0;
        int secMax = 0;
        int globalExact = 0;
        int n = 0;
        for (Observation o : oracle.observations()) {
            if (!sec.supports(o.city())) {
                continue;
            }
            PrayerTimes secTimes = sec.computeTimes(o.city(), o.date());
            PrayerTimes globalTimes = global.computeTimes(o.city(), o.date());
            for (Prayer p : Prayer.values()) {
                int official = minuteOfDay(o.timeFor(p));
                int err = Math.abs(circularError(minuteOfDay(timeFor(secTimes, p)), official));
                if (err == 0) {
                    secExact++;
                }
                if (err <= 1) {
                    secWithin1++;
                }
                secMax = Math.max(secMax, err);
                if (circularError(minuteOfDay(timeFor(globalTimes, p)), official) == 0) {
                    globalExact++;
                }
                n++;
            }
        }
        double secExactPct = 100.0 * secExact / n;
        double within1Pct = 100.0 * secWithin1 / n;
        double improvementPp = secExactPct - 100.0 * globalExact / n;

        SecApproachGates.Result gate =
                SecApproachGates.aggregate(secExactPct, improvementPp, within1Pct, secMax);
        System.out.printf("[aggregate] exact=%.2f%% within1=%.2f%% maxAbs=%d improvement=%.2fpp%n",
                secExactPct, within1Pct, secMax, improvementPp);
        assertThat(gate.failures()).isEmpty();
    }

    @Test
    void everyAcceptedNewCityPassesThePerCityGatesAndFailuresAreNotSupported() {
        SecApproachTrainer.Outcome outcome = trained();

        // The committed offsets are exactly the gated set (preserved + accepted).
        assertThat(outcome.offsets().keySet())
                .allSatisfy(city -> assertThat(sec.supports(city)).as(city).isTrue());

        for (CityResult c : outcome.written()) {
            assertThat(c.gateFailures()).as("%s gate", c.city()).isEmpty();
            SecApproachGates.Result gate = SecApproachGates.perCity(
                    100.0 * c.exactRatio(), c.holdoutExactPercentage(),
                    c.withinOnePercentage(), c.maxAbsoluteError());
            assertThat(gate.passed()).as("%s per-city gate", c.city()).isTrue();
        }
        // Cities that failed the gate must not be servable (fail closed).
        for (CityResult c : outcome.belowBand()) {
            assertThat(c.gateFailures()).as("%s has recorded failures", c.city()).isNotEmpty();
            assertThat(sec.supports(c.city())).as("%s fails closed", c.city()).isFalse();
        }
    }

    @Test
    void existingFourteenCitiesRegressionGuarantees() {
        Map<String, Map<Prayer, Integer>> base = SecApproachTrainingCommand.loadOffsets(BASE14);
        MoroccoCityRegistry registry = new MoroccoCityRegistry();
        for (String city : base.keySet()) {
            // Still supported, and its alias/display name still resolves.
            assertThat(sec.supports(city)).as("%s supported", city).isTrue();
            assertThat(registry.findById(city)).as("%s resolves", city).isPresent();
        }
        // The 14 offsets survive verbatim in the committed set produced by the trainer.
        Map<String, Map<Prayer, Integer>> committed = trained().offsets();
        base.forEach((city, offsets) ->
                assertThat(committed.get(city)).as("%s locked offsets", city).isEqualTo(offsets));
    }

    private static LocalTime timeFor(PrayerTimes t, Prayer prayer) {
        return switch (prayer) {
            case FAJR -> t.fajr();
            case SUNRISE -> t.sunrise();
            case DHUHR -> t.dhuhr();
            case ASR -> t.asr();
            case MAGHRIB -> t.maghrib();
            case ISHA -> t.isha();
        };
    }
}
