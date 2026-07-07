package io.github.cyonecoder.prayertime.audit.optimization;

import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleFunction;

/**
 * Searches a single prayer's parameter independently over a {@link
 * ParameterRange}. This is the one-dimensional building block of the audit: the
 * full Cartesian product across prayers is never enumerated here.
 *
 * <p>For every grid value the supplied factory produces a {@link
 * CalculationCandidate}, which is evaluated against the dataset for the target
 * prayer and scored. Results are returned both in grid order (for interval
 * analysis) and ranked best-first.
 */
public final class ParameterSearchService {

    private final HabousRawEngine engine;
    private final CityLocations locations;

    public ParameterSearchService(HabousRawEngine engine, CityLocations locations) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.locations = Objects.requireNonNull(locations, "locations");
    }

    /** @return the raw engine backing this service. */
    public HabousRawEngine engine() {
        return engine;
    }

    /** @return the city-location resolver backing this service. */
    public CityLocations locations() {
        return locations;
    }

    /** A grid value paired with the result of evaluating its candidate. */
    public record Evaluated(double value, CandidateResult result) {
    }

    /** A contiguous run of grid values that yield the identical published minutes. */
    public record ExactMatchInterval(
            String parameter, double fromValue, double toValue,
            int gridPoints, int exactMatches, int total) {

        /** @return the exact-match percentage shared across the interval. */
        public double exactPercentage() {
            return total == 0 ? 0.0 : 100.0 * exactMatches / total;
        }

        /** @return the width of the interval (toValue - fromValue). */
        public double width() {
            return toValue - fromValue;
        }
    }

    /** The outcome of a one-parameter search. */
    public record PrayerSearchResult(
            Prayer prayer,
            String parameter,
            List<Evaluated> byGrid,
            List<CandidateResult> ranked,
            List<ExactMatchInterval> intervals) {

        /** @return the best candidate found, or empty if the grid was empty. */
        public java.util.Optional<CandidateResult> best() {
            return ranked.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(ranked.get(0));
        }
    }

    /**
     * Runs the one-parameter search.
     *
     * @param prayer           the prayer whose parameter is being searched
     * @param range            the value grid
     * @param dataset          the reference observations
     * @param candidateFactory builds a candidate from a grid value
     * @return the search result (grid-ordered, ranked, and interval analysis)
     */
    public PrayerSearchResult search(
            Prayer prayer,
            ParameterRange range,
            ReferenceDataset dataset,
            DoubleFunction<CalculationCandidate> candidateFactory) {
        Objects.requireNonNull(prayer, "prayer");
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(candidateFactory, "candidateFactory");

        List<Prayer> prayers = List.of(prayer);
        List<Evaluated> byGrid = new ArrayList<>(range.size());
        for (double value : range.values()) {
            CalculationCandidate candidate = candidateFactory.apply(value);
            CandidateResult result =
                    CandidateResult.evaluate(candidate, engine, dataset, prayers, locations);
            byGrid.add(new Evaluated(value, result));
        }

        List<CandidateResult> ranked = new ArrayList<>(byGrid.size());
        for (Evaluated e : byGrid) {
            ranked.add(e.result());
        }
        ranked.sort(CandidateResult.RANKING);

        List<ExactMatchInterval> intervals = intervals(range.name(), byGrid);
        return new PrayerSearchResult(prayer, range.name(), List.copyOf(byGrid),
                List.copyOf(ranked), intervals);
    }

    /**
     * Groups consecutive grid values whose published-minute vectors are
     * identical into intervals. Reports every distinct interval so that when
     * several parameter values are observationally equivalent, none is hidden.
     */
    private static List<ExactMatchInterval> intervals(String parameter, List<Evaluated> byGrid) {
        List<ExactMatchInterval> out = new ArrayList<>();
        if (byGrid.isEmpty()) {
            return out;
        }
        int startIndex = 0;
        List<Integer> currentVector = publishedVector(byGrid.get(0).result());
        for (int i = 1; i <= byGrid.size(); i++) {
            List<Integer> vector = i < byGrid.size() ? publishedVector(byGrid.get(i).result()) : null;
            boolean boundary = (vector == null) || !vector.equals(currentVector);
            if (boundary) {
                Evaluated first = byGrid.get(startIndex);
                Evaluated last = byGrid.get(i - 1);
                CandidateScore score = first.result().score();
                out.add(new ExactMatchInterval(
                        parameter, first.value(), last.value(), i - startIndex,
                        score.exactMinuteMatches(), score.total()));
                startIndex = i;
                currentVector = vector;
            }
        }
        return out;
    }

    private static List<Integer> publishedVector(CandidateResult result) {
        List<Integer> vector = new ArrayList<>(result.residuals().size());
        for (CandidateResult.Residual r : result.residuals()) {
            vector.add(r.computedMinuteOfDay());
        }
        return vector;
    }
}
