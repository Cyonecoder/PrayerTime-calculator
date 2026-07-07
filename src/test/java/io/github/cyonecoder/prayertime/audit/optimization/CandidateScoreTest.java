package io.github.cyonecoder.prayertime.audit.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CandidateScoreTest {

    /** Builds a score with 100 comparisons and the given exact count / tie-break fields. */
    private static CandidateScore score(
            int exact, int maxAbs, double meanAbs, double stdDev, int params, double stability) {
        return new CandidateScore(
                exact, exact, 100, 0.0, meanAbs, maxAbs, stdDev, 1, 100, 1, params, stability);
    }

    @Test
    void exactPercentageIsComputed() {
        assertThat(score(31, 0, 0, 0, 1, 1).exactPercentage()).isEqualTo(31.0);
        assertThat(new CandidateScore(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0).exactPercentage())
                .isEqualTo(0.0);
    }

    @Test
    void rankingPrefersHigherExactPercentageEvenWithWorseMeanError() {
        CandidateScore highExact = score(90, 9, 5.0, 1.0, 1, 1.0);
        CandidateScore lowExactButPreciseMean = score(80, 1, 0.0, 0.0, 1, 1.0);
        List<CandidateScore> ranked = sort(highExact, lowExactButPreciseMean);
        assertThat(ranked).containsExactly(highExact, lowExactButPreciseMean);
    }

    @Test
    void rankingTieBreaksOnMaximumAbsoluteErrorThenMeanThenStdDevThenParamsThenStability() {
        CandidateScore lowerMax = score(90, 2, 3.0, 3.0, 3, 0.5);
        CandidateScore higherMax = score(90, 5, 0.0, 0.0, 1, 1.0);
        assertThat(sort(higherMax, lowerMax)).containsExactly(lowerMax, higherMax);

        // Same exact% and maxErr -> lower MAE wins.
        CandidateScore lowerMae = score(90, 3, 1.0, 9.0, 9, 0.1);
        CandidateScore higherMae = score(90, 3, 2.0, 0.0, 1, 1.0);
        assertThat(sort(higherMae, lowerMae)).containsExactly(lowerMae, higherMae);

        // Same exact%, maxErr, MAE -> lower stdDev wins.
        CandidateScore lowerStd = score(90, 3, 1.0, 1.0, 9, 0.1);
        CandidateScore higherStd = score(90, 3, 1.0, 2.0, 1, 1.0);
        assertThat(sort(higherStd, lowerStd)).containsExactly(lowerStd, higherStd);

        // Same exact%, maxErr, MAE, stdDev -> fewer parameters wins.
        CandidateScore fewerParams = score(90, 3, 1.0, 1.0, 1, 0.1);
        CandidateScore moreParams = score(90, 3, 1.0, 1.0, 4, 1.0);
        assertThat(sort(moreParams, fewerParams)).containsExactly(fewerParams, moreParams);

        // All else equal -> higher cross-city stability wins.
        CandidateScore moreStable = score(90, 3, 1.0, 1.0, 2, 0.9);
        CandidateScore lessStable = score(90, 3, 1.0, 1.0, 2, 0.2);
        assertThat(sort(lessStable, moreStable)).containsExactly(moreStable, lessStable);
    }

    private static List<CandidateScore> sort(CandidateScore... scores) {
        List<CandidateScore> list = new ArrayList<>(List.of(scores));
        list.sort(CandidateScore.RANKING);
        return list;
    }
}
