package io.github.cyonecoder.salatapi.secapproach.configuration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cyonecoder.salatapi.secapproach.model.CalculationCandidate;
import io.github.cyonecoder.salatapi.secapproach.model.OperationOrder;
import io.github.cyonecoder.salatapi.secapproach.model.RoundingPolicy;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code MOROCCO_HABOUS_RECONSTRUCTED} — the frozen best <em>pure global
 * formula</em> reconstructed from the real Habous data: a single
 * {@link CalculationCandidate} applied identically to every city, with no
 * per-city corrections.
 *
 * <p>This is the raw astronomical base that the sec-approach offsets are added
 * to. It is loaded from the bundled {@code /secapproach/morocco-habous-reconstructed.json}
 * (moved from {@code /research/} during extraction).
 *
 * <p>Ported from {@code audit.release.ReconstructedMethod} with Gson replaced by
 * Jackson; see {@code docs/EXTRACTION_MAP.md}.
 */
@Configuration
public class ReconstructedMethod {

    /** The method identifier recorded in provenance. */
    public static final String ID = "MOROCCO_HABOUS_RECONSTRUCTED";

    private static final String RESOURCE = "/secapproach/morocco-habous-reconstructed.json";

    /**
     * @param properties the sec-approach configuration (for the resource location)
     * @return the frozen reconstructed global formula, loaded from the configured resource
     */
    @Bean
    public CalculationCandidate reconstructedCandidate(
            io.github.cyonecoder.salatapi.config.SecApproachProperties properties) {
        return load(properties.reconstructedClasspath());
    }

    /** @return the frozen reconstructed global formula, from the bundled default resource (tests) */
    public static CalculationCandidate frozen() {
        return load(RESOURCE);
    }

    private static CalculationCandidate load(String resource) {
        try (InputStream in = ReconstructedMethod.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Reconstructed method resource not found: " + resource);
            }
            CandidateDto dto = new ObjectMapper().readValue(in, CandidateDto.class);
            return dto.toCandidate();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read reconstructed method: " + resource, e);
        }
    }

    /**
     * The wire form of a candidate. Mirrors {@code audit.model.CandidateSerialization.CandidateDto}
     * so the released resource is read losslessly.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CandidateDto(
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
            double sunriseHorizonDipCoefficient,
            double maghribHorizonDipCoefficient,
            String roundingPolicy,
            String operationOrder,
            boolean elevationAdjustedHorizon) {

        CalculationCandidate toCandidate() {
            return new CalculationCandidate(
                    fajrAngle,
                    ishaAngle,
                    asrShadowFactor,
                    sunriseAltitudeDegrees,
                    maghribAltitudeDegrees,
                    fajrAdjustmentSeconds,
                    sunriseAdjustmentSeconds,
                    dhuhrAdjustmentSeconds,
                    asrAdjustmentSeconds,
                    maghribAdjustmentSeconds,
                    ishaAdjustmentSeconds,
                    sunriseHorizonDipCoefficient,
                    maghribHorizonDipCoefficient,
                    RoundingPolicy.valueOf(roundingPolicy),
                    OperationOrder.valueOf(operationOrder),
                    elevationAdjustedHorizon);
        }
    }
}
