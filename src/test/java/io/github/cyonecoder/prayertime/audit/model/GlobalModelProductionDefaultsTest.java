package io.github.cyonecoder.prayertime.audit.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.domain.method.CalculationParameters;
import io.github.cyonecoder.prayertime.domain.method.ElevationPolicy;
import io.github.cyonecoder.prayertime.domain.method.MoroccoCalculationMethod;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The inference is a research activity: it must not activate the candidate or
 * disturb the production Morocco defaults.
 */
class GlobalModelProductionDefaultsTest {

    @Test
    void runningTheInferenceLeavesProductionDefaultsUnchanged(@TempDir Path out) {
        CalculationParameters before = new MoroccoCalculationMethod().parameters();

        GlobalModelSelection.SelectionResult result = GlobalModelFixtures.selection().run(
                GlobalModelFixtures.realDataset(),
                GlobalModelFixtures.FROZEN_AT, GlobalModelFixtures.COMMIT);
        new GlobalModelReportWriter().write(out, result);

        CalculationParameters after = new MoroccoCalculationMethod().parameters();
        assertThat(after.fajrAngle()).isEqualTo(before.fajrAngle()).isEqualTo(19.0);
        assertThat(after.ishaAngle()).isEqualTo(before.ishaAngle()).isEqualTo(17.0);
        assertThat(after.asrShadowFactor()).isEqualTo(before.asrShadowFactor()).isEqualTo(1.0);
        assertThat(after.sunriseAngle()).isEqualTo(before.sunriseAngle()).isEqualTo(-0.8333);
        assertThat(after.adjustments().dhuhr()).isEqualTo(5);
        assertThat(after.adjustments().maghrib()).isEqualTo(5);
        assertThat(after.elevationPolicy()).isEqualTo(ElevationPolicy.OFFICIAL_CITY_TABLE);
    }

    @Test
    void candidateIsSeparateFromTheProductionDefaultMethod() {
        assertThat(new MoroccoCalculationMethod().id()).isEqualTo("morocco");
        assertThat(MoroccoHabousCandidate.id())
                .isEqualTo("morocco-habous-candidate")
                .isNotEqualTo(new MoroccoCalculationMethod().id());
    }
}
