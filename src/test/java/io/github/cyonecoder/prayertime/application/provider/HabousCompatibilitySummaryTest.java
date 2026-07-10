package io.github.cyonecoder.prayertime.application.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.application.provider.HabousCompatibilityReport.Metrics;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The final-verification gate: OFFICIAL mode must be 100% exact with zero
 * unresolved mismatches, and every mapped city must be covered. Also writes the
 * CI reports that the workflow uploads.
 */
class HabousCompatibilitySummaryTest {

    @Test
    void officialModeIsExactAndCoverageIsComplete() {
        HabousCompatibilityReport report = new HabousCompatibilityReport();
        Metrics m = report.write(Path.of("build/reports/habous-compatibility"));

        System.out.println(report.summary(m));

        assertThat(m.officialExactPercentage())
                .as("Official-mode exact-match percentage").isEqualTo(100.0);
        assertThat(m.officialMismatches())
                .as("Unresolved official mismatches").isEmpty();
        assertThat(m.officialExact()).isTrue();
        assertThat(m.cityCoverageComplete())
                .as("every mapped city has a valid bundled snapshot").isTrue();
        // Formula stays within its accepted band (not required to be 100%).
        assertThat(m.formulaExactPercentage()).isGreaterThan(70.0).isLessThan(100.0);
    }
}
