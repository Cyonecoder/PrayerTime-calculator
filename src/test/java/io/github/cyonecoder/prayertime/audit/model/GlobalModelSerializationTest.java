package io.github.cyonecoder.prayertime.audit.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.model.GlobalModelSelection.SelectionResult;
import io.github.cyonecoder.prayertime.audit.optimization.CalculationCandidate;
import io.github.cyonecoder.prayertime.audit.optimization.OperationOrder;
import io.github.cyonecoder.prayertime.audit.optimization.RoundingPolicy;
import org.junit.jupiter.api.Test;

/** The frozen candidate must serialize losslessly and match the inference. */
class GlobalModelSerializationTest {

    @Test
    void candidateRoundTripsThroughJsonLosslessly() {
        CalculationCandidate original = CalculationCandidate.builder()
                .roundingPolicyFixed(RoundingPolicy.HALF_EVEN)
                .operationOrderFixed(OperationOrder.RAW_THEN_ROUND_THEN_ADJUST)
                .elevationAdjustedHorizon(true)
                .fajrAngle(18.3)
                .ishaAngle(17.7)
                .asrShadowFactor(1.5)
                .sunriseAltitudeDegrees(-1.1)
                .maghribAltitudeDegrees(-1.9)
                .fajrAdjustmentSeconds(30)
                .sunriseAdjustmentSeconds(60)
                .dhuhrAdjustmentSeconds(120)
                .asrAdjustmentSeconds(15)
                .maghribAdjustmentSeconds(45)
                .ishaAdjustmentSeconds(90)
                .sunriseHorizonDipCoefficient(0.029)
                .maghribHorizonDipCoefficient(0.023)
                .build();

        String json = CandidateSerialization.toJson(original);
        CalculationCandidate restored = CandidateSerialization.fromJson(json);

        assertThat(CandidateSerialization.toDto(restored))
                .isEqualTo(CandidateSerialization.toDto(original));
        assertThat(restored.elevationAdjustedHorizon()).isTrue();
        assertThat(restored.sunriseHorizonDipCoefficient()).isEqualTo(0.029);
        assertThat(restored.maghribHorizonDipCoefficient()).isEqualTo(0.023);
    }

    /** A legacy snapshot with no dip fields must round-trip to a zero-dip model. */
    @Test
    void legacySnapshotWithoutDipFieldsLoadsAsZeroDip() {
        String legacyJson = """
                {
                  "fajrAngle": 19.0,
                  "ishaAngle": 17.0,
                  "asrShadowFactor": 1.0,
                  "sunriseAltitudeDegrees": -0.8333,
                  "maghribAltitudeDegrees": -0.8333,
                  "fajrAdjustmentSeconds": 0,
                  "sunriseAdjustmentSeconds": 0,
                  "dhuhrAdjustmentSeconds": 0,
                  "asrAdjustmentSeconds": 0,
                  "maghribAdjustmentSeconds": 0,
                  "ishaAdjustmentSeconds": 0,
                  "roundingPolicy": "NEAREST_MINUTE",
                  "operationOrder": "RAW_THEN_ADJUST_THEN_ROUND",
                  "elevationAdjustedHorizon": false
                }
                """;
        CalculationCandidate restored = CandidateSerialization.fromJson(legacyJson);
        assertThat(restored.sunriseHorizonDipCoefficient()).isZero();
        assertThat(restored.maghribHorizonDipCoefficient()).isZero();
        assertThat(restored.tuned())
                .doesNotContain(CalculationCandidate.Knob.SUNRISE_DIP_COEFFICIENT,
                        CalculationCandidate.Knob.MAGHRIB_DIP_COEFFICIENT);
    }

    @Test
    void frozenCandidateLoadsAndMatchesTheSelectedModel() {
        CalculationCandidate frozen = MoroccoHabousCandidate.frozen();
        SelectionResult result = GlobalModelFixtures.selection().run(
                GlobalModelFixtures.realDataset(),
                GlobalModelFixtures.FROZEN_AT, GlobalModelFixtures.COMMIT);

        // The committed MOROCCO_HABOUS_CANDIDATE snapshot must equal the model the
        // inference actually selects on the committed dataset (guards drift).
        assertThat(CandidateSerialization.toDto(frozen))
                .isEqualTo(CandidateSerialization.toDto(result.selectedCandidate()));
        assertThat(MoroccoHabousCandidate.id()).isEqualTo("morocco-habous-candidate");
    }
}
