package io.github.cyonecoder.prayertime.audit.model;

import io.github.cyonecoder.prayertime.audit.dataset.HabousDatasetSplit;
import io.github.cyonecoder.prayertime.audit.optimization.CalculationCandidate;
import io.github.cyonecoder.prayertime.audit.optimization.CandidateResult;
import io.github.cyonecoder.prayertime.audit.optimization.CityLocations;
import io.github.cyonecoder.prayertime.audit.optimization.HabousRawEngine;
import io.github.cyonecoder.prayertime.audit.optimization.PrayerParameterSearch;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset.Observation;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Orchestrates the global-model inference of Prompt 3.
 *
 * <p>The protocol is strict about data hygiene:
 * <ol>
 *   <li>Every family is fitted on the <strong>training</strong> partition only.</li>
 *   <li>Finalists are chosen and the winner selected on the
 *       <strong>validation</strong> partition.</li>
 *   <li>Parameters are frozen (a timestamp is recorded).</li>
 *   <li>Only then is the <strong>holdout</strong> partition scored — exactly
 *       once — for reporting.</li>
 * </ol>
 * The holdout partition is never read before the freeze, so the holdout numbers
 * stay an honest estimate of generalization. The selected model is a single
 * global candidate with no city-specific corrections.
 */
public final class GlobalModelSelection {

    /** The full list of prayers scored for a candidate. */
    private static final List<Prayer> ALL_PRAYERS = PrayerParameterSearch.SEARCH_ORDER;

    private final HabousRawEngine engine;
    private final CityLocations locations;
    private final GlobalModelFitter fitter;
    private final HabousDatasetSplit split;

    public GlobalModelSelection(
            HabousRawEngine engine, CityLocations locations, HabousDatasetSplit split) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.locations = Objects.requireNonNull(locations, "locations");
        this.split = Objects.requireNonNull(split, "split");
        this.fitter = new GlobalModelFitter(engine, locations);
    }

    /** The observations of each partition, in canonical order (any may be empty). */
    public record Partitions(
            List<Observation> training, List<Observation> validation, List<Observation> holdout) {

        public boolean hasTraining() {
            return !training.isEmpty();
        }

        public boolean hasValidation() {
            return !validation.isEmpty();
        }

        public boolean hasHoldout() {
            return !holdout.isEmpty();
        }
    }

    /** The provenance record pinning the run to its inputs and code. */
    public record Provenance(
            String parametersFrozenAt,
            String trainingDatasetChecksum,
            String validationDatasetChecksum,
            String holdoutDatasetChecksum,
            String codeCommit,
            List<String> trainingCities,
            List<String> validationCities,
            List<String> holdoutCities) {
    }

    /** One family's fit and its validation score. */
    public record FamilyOutcome(
            GlobalModelFamily family,
            CalculationCandidate candidate,
            CandidateResult trainingResult,
            Optional<CandidateResult> validationResult,
            Optional<AcceptanceReport> validationAcceptance,
            boolean finalist) {
    }

    /** The complete selection outcome. */
    public record SelectionResult(
            List<FamilyOutcome> outcomes,
            GlobalModelFamily selectedFamily,
            CalculationCandidate selectedCandidate,
            CandidateResult selectedValidationResult,
            ResidualAnalysis residualAnalysis,
            Optional<CandidateResult> holdoutResult,
            Optional<AcceptanceReport> holdoutAcceptance,
            Provenance provenance,
            Partitions partitions) {
    }

    /**
     * Runs the full inference.
     *
     * @param full             the complete reference dataset (all partitions)
     * @param parametersFrozenAt the freeze instant recorded after selection,
     *                           before the holdout is read
     * @param codeCommit        the source commit the run was produced from
     * @return the selection result
     */
    public SelectionResult run(ReferenceDataset full, Instant parametersFrozenAt, String codeCommit) {
        Objects.requireNonNull(full, "full");
        Objects.requireNonNull(parametersFrozenAt, "parametersFrozenAt");
        Objects.requireNonNull(codeCommit, "codeCommit");

        Partitions partitions = partition(full);
        if (!partitions.hasTraining()) {
            throw new IllegalStateException(
                    "No training observations available; cannot fit a global model.");
        }
        ReferenceDataset training = ReferenceDataset.of(partitions.training());
        Optional<ReferenceDataset> validation = partitions.hasValidation()
                ? Optional.of(ReferenceDataset.of(partitions.validation())) : Optional.empty();

        // 1 & 2 — fit on training, score on validation. The holdout is untouched.
        List<FamilyOutcome> outcomes = new ArrayList<>();
        for (GlobalModelFamily family : GlobalModelFamily.values()) {
            CalculationCandidate candidate = fitter.fit(family, training);
            CandidateResult trainingResult = evaluate(candidate, training);
            Optional<CandidateResult> validationResult =
                    validation.map(v -> evaluate(candidate, v));
            Optional<AcceptanceReport> validationAcceptance =
                    validationResult.map(AcceptanceReport::evaluate);
            outcomes.add(new FamilyOutcome(family, candidate, trainingResult,
                    validationResult, validationAcceptance, false));
        }

        // Rank by validation quality (fall back to training when no validation
        // data exists), deterministically, and pick finalists + the winner.
        outcomes.sort(selectionOrder());
        List<FamilyOutcome> ranked = markFinalists(outcomes);
        FamilyOutcome winner = ranked.get(0);

        // 3 — freeze. The instant is supplied by the caller and recorded now.
        String frozenAt = parametersFrozenAt.toString();

        // 4 — score the holdout exactly once, after the freeze.
        Optional<CandidateResult> holdoutResult = partitions.hasHoldout()
                ? Optional.of(evaluate(winner.candidate(), ReferenceDataset.of(partitions.holdout())))
                : Optional.empty();
        Optional<AcceptanceReport> holdoutAcceptance =
                holdoutResult.map(AcceptanceReport::evaluate);

        CandidateResult selectedValidation = winner.validationResult().orElse(winner.trainingResult());

        // Residual analysis over the fit+validation visible data (holdout excluded).
        List<Observation> visible = new ArrayList<>(partitions.training());
        visible.addAll(partitions.validation());
        CandidateResult visibleResult = evaluate(winner.candidate(), ReferenceDataset.of(visible));
        ResidualAnalysis residualAnalysis = ResidualAnalysis.analyze(visibleResult, locations);

        Provenance provenance = new Provenance(
                frozenAt,
                DatasetChecksum.sha256(partitions.training()),
                DatasetChecksum.sha256(partitions.validation()),
                DatasetChecksum.sha256(partitions.holdout()),
                codeCommit,
                cities(partitions.training()),
                cities(partitions.validation()),
                cities(partitions.holdout()));

        return new SelectionResult(ranked, winner.family(), winner.candidate(), selectedValidation,
                residualAnalysis, holdoutResult, holdoutAcceptance, provenance, partitions);
    }

    // --- Partitioning --------------------------------------------------------

    /** Splits the full dataset's observations into the three partitions. */
    public Partitions partition(ReferenceDataset full) {
        List<Observation> training = new ArrayList<>();
        List<Observation> validation = new ArrayList<>();
        List<Observation> holdout = new ArrayList<>();
        for (Observation o : full.observations()) {
            split.splitFor(o.city()).ifPresent(s -> {
                switch (s) {
                    case TRAINING -> training.add(o);
                    case VALIDATION -> validation.add(o);
                    case HOLDOUT -> holdout.add(o);
                }
            });
        }
        return new Partitions(List.copyOf(training), List.copyOf(validation), List.copyOf(holdout));
    }

    // --- Selection ordering --------------------------------------------------

    private Comparator<FamilyOutcome> selectionOrder() {
        return (a, b) -> {
            CandidateResult ra = a.validationResult().orElse(a.trainingResult());
            CandidateResult rb = b.validationResult().orElse(b.trainingResult());
            int byQuality = CandidateResult.RANKING.compare(ra, rb);
            if (byQuality != 0) {
                return byQuality;
            }
            // Deterministic, parsimony-aware tie-break: prefer the simpler family.
            int byParams = Integer.compare(
                    a.candidate().parameterCount(), b.candidate().parameterCount());
            if (byParams != 0) {
                return byParams;
            }
            return a.family().compareTo(b.family());
        };
    }

    /** Marks finalists: families that meet all validation targets, or the top rank. */
    private List<FamilyOutcome> markFinalists(List<FamilyOutcome> ranked) {
        List<FamilyOutcome> out = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            FamilyOutcome o = ranked.get(i);
            boolean passes = o.validationAcceptance().map(AcceptanceReport::allTargetsMet).orElse(false);
            boolean finalist = passes || i == 0;
            out.add(new FamilyOutcome(o.family(), o.candidate(), o.trainingResult(),
                    o.validationResult(), o.validationAcceptance(), finalist));
        }
        return out;
    }

    private CandidateResult evaluate(CalculationCandidate candidate, ReferenceDataset dataset) {
        return CandidateResult.evaluate(candidate, engine, dataset, ALL_PRAYERS, locations);
    }

    private static List<String> cities(List<Observation> observations) {
        return observations.stream().map(Observation::city).distinct().sorted().toList();
    }
}
