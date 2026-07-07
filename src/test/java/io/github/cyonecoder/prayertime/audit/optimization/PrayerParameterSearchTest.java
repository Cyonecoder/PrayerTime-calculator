package io.github.cyonecoder.prayertime.audit.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.optimization.PrayerParameterSearch.CombinedResult;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PrayerParameterSearchTest {

    private static final Path DATASET = Path.of("research-data/habous");

    private ReferenceDataset casablancaJuly() {
        return ReferenceDataset.load(DATASET)
                .filter(Set.of("casablanca"), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
    }

    private PrayerParameterSearch newSearch() {
        ParameterSearchService service =
                new ParameterSearchService(new HabousRawEngine(), new CityLocations());
        return new PrayerParameterSearch(service);
    }

    @Test
    void chouroukSearchFindsCandidateWithLowerMaeThanTheExistingMinusPointEight333Model() {
        ReferenceDataset dataset = casablancaJuly();
        PrayerParameterSearch search = newSearch();

        CandidateResult best = search
                .searchPrayer(Prayer.SUNRISE, dataset, RoundingPolicy.NEAREST_MINUTE,
                        OperationOrder.RAW_THEN_ADJUST_THEN_ROUND)
                .best().orElseThrow();

        // The existing production model uses a -0.8333 base horizon angle.
        CalculationCandidate baseline = CalculationCandidate.builder()
                .roundingPolicyFixed(RoundingPolicy.NEAREST_MINUTE)
                .operationOrderFixed(OperationOrder.RAW_THEN_ADJUST_THEN_ROUND)
                .sunriseAltitudeDegrees(-0.8333)
                .build();
        CandidateResult baselineResult = CandidateResult.evaluate(
                baseline, new HabousRawEngine(), dataset, List.of(Prayer.SUNRISE), new CityLocations());

        assertThat(baselineResult.score().meanAbsoluteErrorMinutes()).isGreaterThan(0.0);
        assertThat(best.score().meanAbsoluteErrorMinutes())
                .isLessThan(baselineResult.score().meanAbsoluteErrorMinutes());
    }

    @Test
    void combinedValidationScoresAllSixPrayersFromEachPrayersBest() {
        CombinedResult combined = newSearch()
                .searchAll(casablancaJuly(), RoundingPolicy.NEAREST_MINUTE,
                        OperationOrder.RAW_THEN_ADJUST_THEN_ROUND);
        assertThat(combined.combined().score().prayerCount()).isEqualTo(6);
        // Each searched prayer contributed one tuned parameter to the combined model.
        assertThat(combined.combinedCandidate().parameterCount())
                .isEqualTo(PrayerParameterSearch.SEARCH_ORDER.size());
    }

    @Test
    void searchIsDeterministic() {
        var first = newSearch().searchPrayer(Prayer.FAJR, casablancaJuly(),
                RoundingPolicy.NEAREST_MINUTE, OperationOrder.RAW_THEN_ADJUST_THEN_ROUND);
        var second = newSearch().searchPrayer(Prayer.FAJR, casablancaJuly(),
                RoundingPolicy.NEAREST_MINUTE, OperationOrder.RAW_THEN_ADJUST_THEN_ROUND);
        assertThat(second.ranked().stream().map(CandidateResult::signature).toList())
                .isEqualTo(first.ranked().stream().map(CandidateResult::signature).toList());
    }
}
