package io.github.cyonecoder.prayertime.audit.release;

import io.github.cyonecoder.prayertime.audit.model.CandidateSerialization;
import io.github.cyonecoder.prayertime.audit.optimization.CalculationCandidate;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * {@code MOROCCO_HABOUS_RECONSTRUCTED} — the frozen best <em>pure global
 * formula</em> reconstructed from the real Habous data, held as a research
 * artifact.
 *
 * <p>It is a single global {@link CalculationCandidate} applied identically to
 * every city, with no per-city corrections. It is loaded from the bundled,
 * version-controlled snapshot {@code /research/morocco-habous-reconstructed.json}
 * and is <strong>not activated</strong>: production prayer times continue to use
 * {@code MoroccoCalculationMethod} (id {@code "morocco"}).
 */
public final class ReconstructedMethod {

    /** The method identifier accepted by {@code audit validate-habous-method --method}. */
    public static final String ID = "MOROCCO_HABOUS_RECONSTRUCTED";

    private static final String RESOURCE = "/research/morocco-habous-reconstructed.json";

    private ReconstructedMethod() {
    }

    /** @return the frozen reconstructed global formula. */
    public static CalculationCandidate frozen() {
        try (InputStream in = ReconstructedMethod.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Reconstructed method resource not found: " + RESOURCE);
            }
            return CandidateSerialization.fromJson(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read reconstructed method: " + RESOURCE, e);
        }
    }
}
