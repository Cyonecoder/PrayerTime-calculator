/**
 * Research/audit dataset layer: collects, normalizes and stores official Habous
 * monthly prayer schedules for the supported application cities, and reports
 * dataset coverage.
 *
 * <p>This package is <strong>research-only</strong>. It never changes production
 * prayer outputs and never redesigns the application city model: cities keep
 * their existing slugs, and the application-to-Habous relationship lives purely
 * in the external mapping resource
 * {@code /research/habous-city-mapping.json}. The fixed train/validation/holdout
 * partition lives in {@code /research/habous-dataset-split.json}.
 *
 * <h2>Storage</h2>
 * Immutable snapshots are written under
 * {@code research-data/habous/{cityId}/{year}/{month}/} as {@code source.html},
 * {@code source.sha256}, {@code prayer-times.csv} and {@code metadata.json}. A
 * snapshot is never overwritten when its checksum differs; a changed source is
 * saved separately as a revision.
 */
package io.github.cyonecoder.prayertime.audit.dataset;
