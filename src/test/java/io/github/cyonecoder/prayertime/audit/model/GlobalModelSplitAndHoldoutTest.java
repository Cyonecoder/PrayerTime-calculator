package io.github.cyonecoder.prayertime.audit.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.model.GlobalModelSelection.Partitions;
import io.github.cyonecoder.prayertime.audit.model.GlobalModelSelection.SelectionResult;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset.Observation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Enforces the train/validation/holdout hygiene of the inference: cities land in
 * the correct partition, and the holdout partition cannot influence fitting or
 * selection.
 */
class GlobalModelSplitAndHoldoutTest {

    @Test
    void partitionAssignsEachCityToItsSplitAndDropsUnmappedCities() {
        List<Observation> days = new ArrayList<>();
        days.add(GlobalModelFixtures.obs("casablanca", "2026-07-03",
                "04:30", "06:18", "13:37", "17:17", "20:47", "22:19")); // training
        days.add(GlobalModelFixtures.obs("tangier", "2026-07-03",
                "04:25", "06:20", "13:40", "17:20", "20:52", "22:26")); // validation
        days.add(GlobalModelFixtures.obs("tetouan", "2026-07-03",
                "04:24", "06:18", "13:39", "17:19", "20:51", "22:25")); // holdout
        days.add(GlobalModelFixtures.obs("atlantis", "2026-07-03",
                "05:00", "06:30", "13:00", "17:00", "20:00", "22:00")); // unmapped → dropped

        Partitions p = GlobalModelFixtures.selection().partition(ReferenceDataset.of(days));

        assertThat(p.training()).extracting(Observation::city).containsExactly("casablanca");
        assertThat(p.validation()).extracting(Observation::city).containsExactly("tangier");
        assertThat(p.holdout()).extracting(Observation::city).containsExactly("tetouan");
        // "atlantis" is in no split and is silently excluded from every partition.
        assertThat(p.training()).noneMatch(o -> o.city().equals("atlantis"));
        assertThat(p.validation()).noneMatch(o -> o.city().equals("atlantis"));
        assertThat(p.holdout()).noneMatch(o -> o.city().equals("atlantis"));
    }

    @Test
    void holdoutDataCannotInfluenceFittingOrSelection() {
        ReferenceDataset base = GlobalModelFixtures.realDataset();

        // Poison a holdout city (tetouan) with absurd official times. If the
        // fitter or selector ever peeked at the holdout, the chosen parameters
        // would move. They must not.
        List<Observation> poisoned = new ArrayList<>(base.observations());
        poisoned.add(GlobalModelFixtures.obs("tetouan", "2026-07-03",
                "00:01", "00:02", "00:03", "00:04", "00:05", "00:06"));
        poisoned.add(GlobalModelFixtures.obs("tetouan", "2026-07-04",
                "23:54", "23:55", "23:56", "23:57", "23:58", "23:59"));
        ReferenceDataset withHoldout = ReferenceDataset.of(poisoned);

        SelectionResult clean = GlobalModelFixtures.selection()
                .run(base, GlobalModelFixtures.FROZEN_AT, GlobalModelFixtures.COMMIT);
        SelectionResult poisonedRun = GlobalModelFixtures.selection()
                .run(withHoldout, GlobalModelFixtures.FROZEN_AT, GlobalModelFixtures.COMMIT);

        assertThat(poisonedRun.selectedFamily()).isEqualTo(clean.selectedFamily());
        assertThat(CandidateSerialization.toDto(poisonedRun.selectedCandidate()))
                .isEqualTo(CandidateSerialization.toDto(clean.selectedCandidate()));
        // Training/validation inputs are unchanged, so their checksums match; only
        // the holdout checksum differs (it now has observations).
        assertThat(poisonedRun.provenance().trainingDatasetChecksum())
                .isEqualTo(clean.provenance().trainingDatasetChecksum());
        assertThat(poisonedRun.provenance().validationDatasetChecksum())
                .isEqualTo(clean.provenance().validationDatasetChecksum());
        assertThat(poisonedRun.provenance().holdoutDatasetChecksum())
                .isNotEqualTo(clean.provenance().holdoutDatasetChecksum());
    }
}
