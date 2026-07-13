/**
 * Deterministic sec-approach trainer (research/audit only, kept out of the core
 * domain). Given the official Habous research snapshots, it pairs the raw
 * reconstructed astronomical seconds with the official minute for every
 * (city, prayer, date), then finds each (city, prayer)'s best constant
 * seconds-offset by an exhaustive, bounded, integer search with a fully
 * deterministic lexicographic tie-break.
 *
 * <p>It computes no per-date corrections, uses no randomness, no floating-point
 * tie-break, and no machine learning — the same dataset always produces the same
 * offsets. Output is the production-shaped offsets file (consumed by
 * {@code SecondsApproachPrayerTimesProvider}) plus a per-city, per-prayer metrics
 * report. It never contacts Habous.
 */
package io.github.cyonecoder.prayertime.audit.secapproach;
