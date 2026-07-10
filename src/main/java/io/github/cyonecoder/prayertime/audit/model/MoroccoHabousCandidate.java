package io.github.cyonecoder.prayertime.audit.model;

import io.github.cyonecoder.prayertime.audit.optimization.CalculationCandidate;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * {@code MOROCCO_HABOUS_CANDIDATE} — the frozen global model selected by the
 * Prompt-3 inference, held as a research artifact.
 *
 * <p>This is <strong>not activated</strong>. Production prayer times continue to
 * use {@code MoroccoCalculationMethod} (id {@code "morocco"}); this candidate is
 * deliberately kept out of the production method wiring. It is exposed only so
 * the audit can carry the selected parameters forward and compare against them.
 *
 * <p>The selected family ({@code SEPARATE_SUNRISE_SUNSET_ALTITUDES}) uses
 * distinct sunrise and sunset horizon altitudes, which the production
 * {@code CalculationParameters} (a single {@code sunriseAngle}) cannot represent,
 * so the candidate is stored as an audit-layer {@link CalculationCandidate}
 * loaded from the bundled, version-controlled snapshot
 * {@code /research/morocco-habous-candidate.json}. It is a single global
 * parameter set applied identically to every city — no city-specific
 * corrections.
 */
public final class MoroccoHabousCandidate {

    /** Stable machine identifier for the candidate model. */
    public static final String ID = "morocco-habous-candidate";

    /** Human-readable name. */
    public static final String DISPLAY_NAME = "Morocco (Habous) — global candidate";

    private static final String RESOURCE = "/research/morocco-habous-candidate.json";

    private MoroccoHabousCandidate() {
    }

    /** @return the stable identifier, {@value #ID}. */
    public static String id() {
        return ID;
    }

    /** @return the frozen selected global candidate loaded from the bundled snapshot. */
    public static CalculationCandidate frozen() {
        try (InputStream in = MoroccoHabousCandidate.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Candidate resource not found: " + RESOURCE);
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return CandidateSerialization.fromJson(json);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read candidate resource: " + RESOURCE, e);
        }
    }
}
