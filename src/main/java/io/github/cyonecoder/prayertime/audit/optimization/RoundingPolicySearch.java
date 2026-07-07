package io.github.cyonecoder.prayertime.audit.optimization;

import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Searches the publication rounding policy and operation order for a fixed set
 * of astronomical parameters. Evaluates every {@link RoundingPolicy} crossed
 * with every {@link OperationOrder} and ranks the resulting candidates.
 *
 * <p>The astronomical knobs come from a base candidate; only the rounding policy
 * and operation order vary, so the search is small (policies × orders).
 */
public final class RoundingPolicySearch {

    private final HabousRawEngine engine;
    private final CityLocations locations;

    public RoundingPolicySearch(HabousRawEngine engine, CityLocations locations) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.locations = Objects.requireNonNull(locations, "locations");
    }

    /**
     * Evaluates every policy × order combination against the dataset.
     *
     * @param base    the base candidate providing the astronomical parameters
     * @param dataset the reference observations
     * @param prayers the prayers to score
     * @return the candidates, ranked best-first
     */
    public List<CandidateResult> search(
            CalculationCandidate base, ReferenceDataset dataset, List<Prayer> prayers) {
        Objects.requireNonNull(base, "base");
        List<CandidateResult> results = new ArrayList<>();
        for (OperationOrder order : OperationOrder.values()) {
            for (RoundingPolicy policy : RoundingPolicy.values()) {
                CalculationCandidate candidate = base.toBuilder()
                        .roundingPolicy(policy)
                        .operationOrder(order)
                        .build();
                results.add(CandidateResult.evaluate(candidate, engine, dataset, prayers, locations));
            }
        }
        results.sort(CandidateResult.RANKING);
        return List.copyOf(results);
    }
}
