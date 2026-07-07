package io.github.cyonecoder.prayertime.audit.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.cyonecoder.prayertime.audit.optimization.CalculationCandidate;
import io.github.cyonecoder.prayertime.audit.optimization.OperationOrder;
import io.github.cyonecoder.prayertime.audit.optimization.RoundingPolicy;
import java.util.Objects;

/**
 * Lossless JSON serialization of a {@link CalculationCandidate}. Round-trips
 * every field — angles, altitudes, adjustments, rounding policy, operation order
 * and the elevation-adjusted flag — so a frozen global candidate can be written
 * to {@code selected-model.json} and re-read exactly.
 */
public final class CandidateSerialization {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private CandidateSerialization() {
    }

    /** The wire form of a candidate. Field order is stable for byte-reproducibility. */
    public record CandidateDto(
            double fajrAngle,
            double ishaAngle,
            double asrShadowFactor,
            double sunriseAltitudeDegrees,
            double maghribAltitudeDegrees,
            int fajrAdjustmentSeconds,
            int sunriseAdjustmentSeconds,
            int dhuhrAdjustmentSeconds,
            int asrAdjustmentSeconds,
            int maghribAdjustmentSeconds,
            int ishaAdjustmentSeconds,
            String roundingPolicy,
            String operationOrder,
            boolean elevationAdjustedHorizon) {
    }

    /** @return the DTO view of a candidate. */
    public static CandidateDto toDto(CalculationCandidate c) {
        Objects.requireNonNull(c, "candidate");
        return new CandidateDto(
                c.fajrAngle(), c.ishaAngle(), c.asrShadowFactor(),
                c.sunriseAltitudeDegrees(), c.maghribAltitudeDegrees(),
                c.fajrAdjustmentSeconds(), c.sunriseAdjustmentSeconds(), c.dhuhrAdjustmentSeconds(),
                c.asrAdjustmentSeconds(), c.maghribAdjustmentSeconds(), c.ishaAdjustmentSeconds(),
                c.roundingPolicy().name(), c.operationOrder().name(), c.elevationAdjustedHorizon());
    }

    /** @return a candidate rebuilt from a DTO (values are set as fixed, not tuned). */
    public static CalculationCandidate fromDto(CandidateDto d) {
        Objects.requireNonNull(d, "dto");
        return CalculationCandidate.builder()
                .roundingPolicyFixed(RoundingPolicy.valueOf(d.roundingPolicy()))
                .operationOrderFixed(OperationOrder.valueOf(d.operationOrder()))
                .elevationAdjustedHorizon(d.elevationAdjustedHorizon())
                .fajrAngle(d.fajrAngle())
                .ishaAngle(d.ishaAngle())
                .asrShadowFactor(d.asrShadowFactor())
                .sunriseAltitudeDegrees(d.sunriseAltitudeDegrees())
                .maghribAltitudeDegrees(d.maghribAltitudeDegrees())
                .fajrAdjustmentSeconds(d.fajrAdjustmentSeconds())
                .sunriseAdjustmentSeconds(d.sunriseAdjustmentSeconds())
                .dhuhrAdjustmentSeconds(d.dhuhrAdjustmentSeconds())
                .asrAdjustmentSeconds(d.asrAdjustmentSeconds())
                .maghribAdjustmentSeconds(d.maghribAdjustmentSeconds())
                .ishaAdjustmentSeconds(d.ishaAdjustmentSeconds())
                .build();
    }

    /** @return the candidate serialized as pretty JSON. */
    public static String toJson(CalculationCandidate c) {
        return GSON.toJson(toDto(c));
    }

    /** @return a candidate parsed from JSON produced by {@link #toJson}. */
    public static CalculationCandidate fromJson(String json) {
        return fromDto(GSON.fromJson(json, CandidateDto.class));
    }
}
