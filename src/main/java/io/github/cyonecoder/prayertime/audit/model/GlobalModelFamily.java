package io.github.cyonecoder.prayertime.audit.model;

/**
 * The named global calculation-model candidates the audit compares. Each family
 * is a <em>global</em> model: a single parameter set applied identically to
 * every city, with no city-specific corrections. They differ only in how the
 * sunrise/Maghrib horizon is modelled and whether fixed precaution buffers are
 * used.
 *
 * <p>The families map onto the Chourouk/Maghrib comparison of the audit prompt:
 * <ul>
 *   <li>{@link #GLOBAL_FIXED_ALTITUDES} — one fitted effective solar altitude
 *       shared by sunrise and sunset.</li>
 *   <li>{@link #GLOBAL_FIXED_ALTITUDES_WITH_PRECAUTIONS} — the standard
 *       −0.8333° altitude plus fitted fixed precaution buffers.</li>
 *   <li>{@link #ELEVATION_ADJUSTED_HORIZON} — the standard altitude corrected by
 *       a global elevation-dip function (never a per-city constant).</li>
 *   <li>{@link #SEPARATE_SUNRISE_SUNSET_ALTITUDES} — independent fitted altitudes
 *       for sunrise and for sunset.</li>
 *   <li>{@link #CURRENT_LEGACY_MODEL} — the production Morocco method, fixed, as
 *       the comparison baseline.</li>
 * </ul>
 */
public enum GlobalModelFamily {

    GLOBAL_FIXED_ALTITUDES(
            "Global fixed altitudes", Horizon.FITTED_SHARED, false, false),

    GLOBAL_FIXED_ALTITUDES_WITH_PRECAUTIONS(
            "Global fixed altitudes with precautions", Horizon.STANDARD_WITH_PRECAUTION, true, false),

    ELEVATION_ADJUSTED_HORIZON(
            "Elevation-adjusted horizon", Horizon.FITTED_SHARED, false, true),

    SEPARATE_SUNRISE_SUNSET_ALTITUDES(
            "Separate sunrise/sunset altitudes", Horizon.FITTED_SEPARATE, false, false),

    CURRENT_LEGACY_MODEL(
            "Current legacy model", Horizon.LEGACY_FIXED, false, false);

    /** How the sunrise/Maghrib horizon altitude is chosen for a family. */
    public enum Horizon {
        /** One fitted altitude shared by sunrise and sunset. */
        FITTED_SHARED,
        /** Independent fitted altitudes for sunrise and sunset. */
        FITTED_SEPARATE,
        /** Standard −0.8333° altitude, offsets absorbed by fitted precaution buffers. */
        STANDARD_WITH_PRECAUTION,
        /** The frozen production Morocco altitude and buffers; nothing is fitted. */
        LEGACY_FIXED
    }

    private final String label;
    private final Horizon horizon;
    private final boolean precautions;
    private final boolean elevationAdjusted;

    GlobalModelFamily(String label, Horizon horizon, boolean precautions, boolean elevationAdjusted) {
        this.label = label;
        this.horizon = horizon;
        this.precautions = precautions;
        this.elevationAdjusted = elevationAdjusted;
    }

    /** @return a short human-readable label. */
    public String label() {
        return label;
    }

    /** @return how this family chooses the sunrise/Maghrib altitude. */
    public Horizon horizon() {
        return horizon;
    }

    /** @return whether sunrise and Maghrib share one fitted altitude. */
    public boolean tiesAltitudes() {
        return horizon == Horizon.FITTED_SHARED || horizon == Horizon.STANDARD_WITH_PRECAUTION;
    }

    /** @return whether the family fits fixed precaution buffers over a standard altitude. */
    public boolean usesPrecautions() {
        return precautions;
    }

    /** @return whether the family applies the global elevation-dip correction. */
    public boolean isElevationAdjusted() {
        return elevationAdjusted;
    }

    /** @return whether this family fits parameters (false for the frozen legacy baseline). */
    public boolean isFitted() {
        return this != CURRENT_LEGACY_MODEL;
    }
}
