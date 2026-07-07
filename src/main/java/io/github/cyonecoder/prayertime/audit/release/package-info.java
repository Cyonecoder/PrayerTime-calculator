/**
 * Release validation (Prompt 6): validating the reconstructed pure global Habous
 * method against all mapped cities and producing a GO / NO-GO release decision.
 *
 * <p>{@link io.github.cyonecoder.prayertime.audit.release.HabousMethodValidator}
 * reports three separate result groups — pure formula, plus approved city
 * corrections, plus reviewed official overrides — and never merges them.
 * {@link io.github.cyonecoder.prayertime.audit.release.ReleaseDecision} applies
 * the release thresholds honestly (no metric is softened to pass), and
 * {@link io.github.cyonecoder.prayertime.audit.release.ReleaseReportWriter}
 * writes the seven {@code build/reports/habous-release} deliverables. The
 * correction/override layer is empty by design: the reconstruction is a pure
 * global formula with no per-city corrections.
 */
package io.github.cyonecoder.prayertime.audit.release;
