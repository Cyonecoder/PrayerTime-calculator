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
    void bestCandidateMaximizesExactMinutesAndCountsConsistently() {
        PrayerSearchResult result = searchSunrise();
        CandidateResult best = result.best().orElseThrow();
        CandidateScore s = best.score();
        // Real Habous sunrise is NOT perfectly reproducible by a single global
        // altitude, so we do not assert 100%. We assert the counting is coherent
        // and that the ranked best genuinely maximises exact minutes over the grid.
        assertThat(s.total()).isEqualTo(casablancaJuly().observations().size());
        assertThat(s.exactMinuteMatches()).isBetween(0, s.total());
        assertThat(s.withinOneMinuteMatches()).isGreaterThanOrEqualTo(s.exactMinuteMatches());
        assertThat(s.meanAbsoluteErrorMinutes()).isGreaterThanOrEqualTo(0.0);
        int maxExactOverGrid = result.ranked().stream()
                .mapToInt(r -> r.score().exactMinuteMatches()).max().orElseThrow();
        assertThat(s.exactMinuteMatches()).isEqualTo(maxExactOverGrid);
    }

    @Test
    void reportsFullEquivalentIntervalNotASingleValue() {
        PrayerSearchResult result = searchSunrise();
        // Several observationally-equivalent altitudes collapse to identical
        // published minutes, so at least one reported interval spans many grid
        // points (regardless of whether any interval is a perfect reproduction).
        ParameterSearchService.ExactMatchInterval widest = result.intervals().stream()
                .max(java.util.Comparator.comparingInt(
                        ParameterSearchService.ExactMatchInterval::gridPoints))
                .orElseThrow();
        assertThat(widest.gridPoints()).isGreaterThan(1);
        assertThat(widest.width()).isGreaterThan(0.0);
        assertThat(widest.fromValue()).isLessThan(widest.toValue());
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
