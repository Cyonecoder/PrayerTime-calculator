package io.github.cyonecoder.prayertime.audit.optimization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cyonecoder.prayertime.application.provider.SecondsApproachPrayerTimesProvider;
import io.github.cyonecoder.prayertime.audit.optimization.SecondsApproachOffsetFitter.CityFit;
import io.github.cyonecoder.prayertime.audit.release.ReconstructedMethod;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The deterministic offset fitter must reproduce (and never underperform) the
 * committed 14-city sec-approach offsets on the same official research dataset.
 * This is the training/recalibration machinery used to onboard new cities, so it
 * is pinned against the known-good in-sample result.
 */
class SecondsApproachOffsetFitterTest {

    private static final Path DATASET = Path.of("research-data/habous");

    private final ReferenceDataset dataset = ReferenceDataset.load(DATASET);
    private final SecondsApproachOffsetFitter fitter = new SecondsApproachOffsetFitter(
            new MoroccoCityRegistry(), new HabousRawEngine(), ReconstructedMethod.frozen());
    private final SecondsApproachPrayerTimesProvider committed =
            new SecondsApproachPrayerTimesProvider();

    @Test
    void fitsEveryDatasetCityAndIsDeterministic() {
        Map<String, CityFit> first = fitter.fitAll(dataset);
        Map<String, CityFit> second = fitter.fitAll(dataset);
        assertThat(first).hasSize(14);
        // Identical offsets across two runs: the search is fully deterministic.
        first.forEach((city, fit) ->
                assertThat(fit.offsets()).as(city).isEqualTo(second.get(city).offsets()));
    }

    @Test
    void reachesTheAccuracyBandAndMatchesOrBeatsCommittedOffsets() {
        Map<String, CityFit> fits = fitter.fitAll(dataset);

        int fittedExact = 0;
        int committedExact = 0;
        int total = 0;
        for (CityFit fit : fits.values()) {
            fittedExact += fit.exactMatches();
            total += fit.total();
            committedExact += committedExactMatches(fit.cityId());
        }

        double fittedRatio = (double) fittedExact / total;
        double committedRatio = (double) committedExact / total;
        System.out.printf(
                "[fitter] exact=%.2f%%  committed=%.2f%%  (n=%d)%n",
                100 * fittedRatio, 100 * committedRatio, total);

        // The fitter maximises in-sample exact agreement, so by construction it must
        // meet the sec-approach band and never do worse than the hand-committed set.
        assertThat(fittedRatio).isGreaterThanOrEqualTo(0.95);
        assertThat(fittedRatio).isGreaterThanOrEqualTo(committedRatio - 1e-9);
    }

    @Test
    void everyFittedOffsetIsWithinASaneSubMinuteBand() {
        for (CityFit fit : fitter.fitAll(dataset).values()) {
            for (Prayer p : Prayer.values()) {
                assertThat(Math.abs(fit.offsets().get(p)))
                        .as(fit.cityId() + "/" + p)
                        .isLessThanOrEqualTo(600);
            }
        }
    }

    @Test
    void emptyObservationsAreRejected() {
        assertThatThrownBy(() -> fitter.fit("casablanca", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Exact-minute matches the currently committed offsets score for one city. */
    private int committedExactMatches(String cityId) {
        int exact = 0;
        for (ReferenceDataset.Observation o :
                dataset.filter(java.util.Set.of(cityId), null, null).observations()) {
            PrayerTimes calc = committed.computeTimes(cityId, o.date());
            for (Prayer p : Prayer.values()) {
                if (timeFor(calc, p).equals(o.timeFor(p))) {
                    exact++;
                }
            }
        }
        return exact;
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
