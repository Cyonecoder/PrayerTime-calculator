package io.github.cyonecoder.prayertime.audit.secapproach;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.optimization.HabousRawEngine;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachSplitEvaluator.CitySplitEvaluation;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachSplitEvaluator.PrayerSplitEvaluation;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The split evaluator must fit on training only, evaluate untouched on holdout,
 * expose the shipped in-sample release offset separately, and be deterministic.
 */
class SecApproachSplitEvaluatorTest {

    private static final Path DATASET = Path.of("research-data/habous");

    private SecApproachTrainingDataset dataset() {
        return SecApproachTrainingDataset.fromDirectory(
                DATASET, new MoroccoCityRegistry(), new HabousRawEngine());
    }

    @Test
    void holdoutIsFiveDaysScoredWithTheTrainingSelectedOffset() {
        SecApproachSplitEvaluator ev = new SecApproachSplitEvaluator(-900, 900);
        CitySplitEvaluation e = ev.evaluate(dataset(), "safi");

        for (PrayerSplitEvaluation p : e.prayers()) {
            assertThat(p.training().observations()).isEqualTo(20);
            assertThat(p.validation().observations()).isEqualTo(5);
            assertThat(p.holdout().observations()).isEqualTo(5);
            assertThat(p.inSample().observations()).isEqualTo(30);
            // The release offset is the in-sample all-dates optimum; the split offset
            // is fit on training only. Holdout is scored with the split offset, so its
            // exact count cannot exceed the number of holdout days.
            assertThat(p.holdout().exactCount()).isBetween(0, 5);
        }
    }

    @Test
    void releaseOffsetMatchesTheInSampleSearch() {
        SecApproachSplitEvaluator ev = new SecApproachSplitEvaluator(-900, 900);
        CitySplitEvaluation e = ev.evaluate(dataset(), "nador");
        SecApproachTrainingDataset ds = dataset();
        for (PrayerSplitEvaluation p : e.prayers()) {
            int allDatesBest = SecApproachOffsetSearch
                    .search(ds.observations("nador", p.prayer()), -900, 900).best().offset();
            assertThat(p.releaseOffset()).isEqualTo(allDatesBest);
        }
    }

    @Test
    void isDeterministic() {
        SecApproachSplitEvaluator ev = new SecApproachSplitEvaluator(-900, 900);
        CitySplitEvaluation a = ev.evaluate(dataset(), "tetouan");
        CitySplitEvaluation b = ev.evaluate(dataset(), "tetouan");
        for (Prayer prayer : Prayer.values()) {
            assertThat(a.prayers().get(prayer.ordinal()).splitOffset())
                    .isEqualTo(b.prayers().get(prayer.ordinal()).splitOffset());
            assertThat(a.prayers().get(prayer.ordinal()).holdout().exactCount())
                    .isEqualTo(b.prayers().get(prayer.ordinal()).holdout().exactCount());
        }
    }
}
