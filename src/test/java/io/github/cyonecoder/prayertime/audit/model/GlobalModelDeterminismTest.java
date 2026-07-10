package io.github.cyonecoder.prayertime.audit.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.model.GlobalModelSelection.SelectionResult;
import io.github.cyonecoder.prayertime.audit.optimization.CalculationCandidate;
import io.github.cyonecoder.prayertime.audit.optimization.CityLocations;
import io.github.cyonecoder.prayertime.audit.optimization.HabousRawEngine;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset;
import org.junit.jupiter.api.Test;

/** Parameter selection must be fully deterministic given identical inputs. */
class GlobalModelDeterminismTest {

    @Test
    void selectionIsReproducible() {
        ReferenceDataset data = GlobalModelFixtures.realDataset();
        SelectionResult a = GlobalModelFixtures.selection()
                .run(data, GlobalModelFixtures.FROZEN_AT, GlobalModelFixtures.COMMIT);
        SelectionResult b = GlobalModelFixtures.selection()
                .run(data, GlobalModelFixtures.FROZEN_AT, GlobalModelFixtures.COMMIT);

        assertThat(b.selectedFamily()).isEqualTo(a.selectedFamily());
        assertThat(CandidateSerialization.toDto(b.selectedCandidate()))
                .isEqualTo(CandidateSerialization.toDto(a.selectedCandidate()));
        assertThat(b.outcomes()).extracting(o -> o.family().name())
                .containsExactlyElementsOf(a.outcomes().stream().map(o -> o.family().name()).toList());
        assertThat(b.provenance()).isEqualTo(a.provenance());
    }

    @Test
    void fittingEachFamilyIsReproducible() {
        ReferenceDataset training = ReferenceDataset.of(
                GlobalModelFixtures.selection()
                        .partition(GlobalModelFixtures.realDataset()).training());
        GlobalModelFitter fitter =
                new GlobalModelFitter(new HabousRawEngine(), new CityLocations());

        for (GlobalModelFamily family : GlobalModelFamily.values()) {
            CalculationCandidate first = fitter.fit(family, training);
            CalculationCandidate second = fitter.fit(family, training);
            assertThat(second).as("family %s", family).isEqualTo(first);
        }
    }
}
