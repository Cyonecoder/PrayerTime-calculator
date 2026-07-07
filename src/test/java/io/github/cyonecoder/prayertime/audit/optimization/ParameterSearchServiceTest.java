package io.github.cyonecoder.prayertime.audit.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.optimization.ParameterSearchService.PrayerSearchResult;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.function.DoubleFunction;
import org.junit.jupiter.api.Test;

class ParameterSearchServiceTest {

    private static final Path DATASET = Path.of("research-data/habous");

    private ReferenceDataset casablancaJuly() {
        return ReferenceDataset.load(DATASET)
                .filter(Set.of("casablanca"), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
    }

    private static DoubleFunction<CalculationCandidate> sunriseFactory() {
        return v -> CalculationCandidate.builder()
                .roundingPolicyFixed(RoundingPolicy.NEAREST_MINUTE)
                .operationOrderFixed(OperationOrder.RAW_THEN_ADJUST_THEN_ROUND)
                .sunriseAltitudeDegrees(v)
                .build();
    }

    private PrayerSearchResult searchSunrise() {
        ParameterSearchService service =
                new ParameterSearchService(new HabousRawEngine(), new CityLocations());
        ParameterRange range = ParameterRange.of("sunriseAltitudeDegrees", -1.30, -1.00, 0.001);
        return service.search(Prayer.SUNRISE, range, casablancaJuly(), sunriseFactory());
    }

    @Test
    void bestCandidateReachesExactMatchAndCountsExactMinutes() {
        PrayerSearchResult result = searchSunrise();
        CandidateResult best = result.best().orElseThrow();
        // Casablanca 2026-07-03 sunrise is reproducible exactly within this window.
        assertThat(best.score().exactMinuteMatches()).isEqualTo(best.score().total());
        assertThat(best.score().meanAbsoluteErrorMinutes()).isEqualTo(0.0);
        assertThat(best.score().exactPercentage()).isEqualTo(100.0);
    }

    @Test
    void reportsFullEquivalentIntervalNotASingleValue() {
        PrayerSearchResult result = searchSunrise();
        ParameterSearchService.ExactMatchInterval bestInterval = result.intervals().stream()
                .filter(iv -> iv.exactPercentage() == 100.0)
                .findFirst()
                .orElseThrow();
        // Several observationally-equivalent altitudes exist; the interval is wide.
        assertThat(bestInterval.gridPoints()).isGreaterThan(1);
        assertThat(bestInterval.width()).isGreaterThan(0.0);
        assertThat(bestInterval.fromValue()).isLessThan(bestInterval.toValue());
    }

    @Test
    void rankingIsDeterministicAcrossRuns() {
        List<String> firstRun = searchSunrise().ranked().stream()
                .map(CandidateResult::signature).toList();
        List<String> secondRun = searchSunrise().ranked().stream()
                .map(CandidateResult::signature).toList();
        assertThat(secondRun).isEqualTo(firstRun);
        // And the ranked order genuinely respects the ranking comparator.
        assertThat(searchSunrise().ranked()).isSortedAccordingTo(CandidateResult.RANKING);
    }
}
