package io.github.cyonecoder.prayertime.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.legacy.LegacyMain.LegacyTimes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Characterization ("golden master") test that pins the LEGACY formula's
 * current output so any accidental change to it is caught immediately.
 *
 * <p><strong>These expected values are NOT official Morocco (Habous) reference
 * times.</strong> They are simply the raw output of the existing legacy
 * calculation, captured in the Prompt 1 baseline
 * ({@code docs/audit/baseline.md}). This test asserts nothing about
 * correctness — only that behaviour is unchanged. It must be kept separate
 * from future correctness tests, and it is expected to be updated (or deleted)
 * only when the legacy calculation is deliberately replaced.
 */
class LegacyCharacterizationTest {

    // Original hard-coded inputs (Casablanca, 2024-03-17 23:59:59 UTC).
    private static final int Y = 2024;
    private static final int M = 3;
    private static final int D = 17;
    private static final int H = 23;
    private static final int MIN = 59;
    private static final int SEC = 59;
    private static final int Z = 0;
    private static final double LONG = -6.7480954;
    private static final double ELEVATION = 96;
    private static final double LAT = 34.0361152;
    private static final double FAJR_ANGLE = 18.5;
    private static final double ISHA_ANGLE = 17;

    @Test
    @DisplayName("Legacy formula still produces the Prompt 1 baseline output "
            + "(behavior preservation only — NOT official Morocco reference times)")
    void preservesLegacyBaselineBehaviorOnly() {
        LegacyTimes actual =
                LegacyMain.compute(Y, M, D, H, MIN, SEC, Z, LONG, ELEVATION, LAT, FAJR_ANGLE, ISHA_ANGLE);

        // Baseline values captured verbatim in docs/audit/baseline.md.
        // NOT official Morocco times — legacy behaviour snapshot only.
        assertThat(actual.fajr()).isEqualTo("05:07:23");
        assertThat(actual.sunrise()).isEqualTo("06:31:36");
        assertThat(actual.dhuhr()).isEqualTo("12:41:01");
        assertThat(actual.asr()).isEqualTo("16:00:59");
        assertThat(actual.maghrib()).isEqualTo("18:41:55");
        assertThat(actual.isha()).isEqualTo("19:55:15");
    }
}
