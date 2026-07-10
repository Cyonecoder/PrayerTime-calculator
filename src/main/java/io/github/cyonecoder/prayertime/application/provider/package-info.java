/**
 * The prayer-time provider layer: source modes ({@link
 * io.github.cyonecoder.prayertime.application.provider.PrayerSourceMode}), the
 * {@link io.github.cyonecoder.prayertime.application.provider.PrayerTimesProvider}
 * contract, and the calculated / official / compatible providers.
 *
 * <p>{@code CALCULATED} serves the reconstructed astronomical method;
 * {@code OFFICIAL} serves the exact validated Habous timetable (or fails closed);
 * {@code AUTO} prefers official and falls back to calculation with a flag;
 * {@code COMPARE} returns both plus differences. Official values are served
 * verbatim and never rounded, corrected, or recomputed.
 */
package io.github.cyonecoder.prayertime.application.provider;
