package io.github.cyonecoder.prayertime.application.provider;

/**
 * How a caller wants prayer times sourced.
 *
 * <ul>
 *   <li>{@link #CALCULATED} — always the reconstructed astronomical calculation
 *       ({@code MOROCCO_HABOUS_RECONSTRUCTED}); no official override.</li>
 *   <li>{@link #OFFICIAL} — the exact validated Habous timetable value, or a hard
 *       {@code HABOUS_OFFICIAL_DATA_UNAVAILABLE} failure; never silently
 *       calculated.</li>
 *   <li>{@link #AUTO} — official when a valid official source exists, otherwise a
 *       clearly-flagged reconstructed-calculation fallback.</li>
 *   <li>{@link #COMPARE} — both official and reconstructed results plus their
 *       per-prayer differences.</li>
 * </ul>
 */
public enum PrayerSourceMode {
    CALCULATED,
    OFFICIAL,
    AUTO,
    COMPARE
}
