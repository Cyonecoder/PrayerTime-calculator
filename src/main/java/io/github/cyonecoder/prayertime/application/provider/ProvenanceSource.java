package io.github.cyonecoder.prayertime.application.provider;

/**
 * The concrete origin of a served prayer-time value, recorded on every result
 * for provenance. Distinct from {@link PrayerSourceMode}, which is what the
 * caller <em>asked</em> for; this is what was actually produced.
 */
public enum ProvenanceSource {

    /** The exact, unmodified official Habous timetable value. */
    HABOUS_OFFICIAL(true),

    /** The reconstructed astronomical calculation ({@code MOROCCO_HABOUS_RECONSTRUCTED}). */
    ASTRONOMICAL_RECONSTRUCTED(false);

    private final boolean official;

    ProvenanceSource(boolean official) {
        this.official = official;
    }

    /** @return whether this source is the official authority (never recomputed). */
    public boolean official() {
        return official;
    }
}
