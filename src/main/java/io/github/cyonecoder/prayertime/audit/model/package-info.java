/**
 * Global Habous model inference (Prompt 3): fitting the simplest single global
 * parameter set that best reproduces the official schedule across cities and
 * seasons, with no city-specific corrections.
 *
 * <p>The pipeline fits each {@link
 * io.github.cyonecoder.prayertime.audit.model.GlobalModelFamily} on the training
 * partition, selects a winner on validation, freezes it, and scores the holdout
 * exactly once ({@link
 * io.github.cyonecoder.prayertime.audit.model.GlobalModelSelection}). It reuses
 * the Prompt-2 raw engine and scoring verbatim and never mutates production
 * configuration; the selected model is exposed as the inactive {@link
 * io.github.cyonecoder.prayertime.audit.model.MoroccoHabousCandidate}.
 */
package io.github.cyonecoder.prayertime.audit.model;
