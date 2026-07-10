package io.github.cyonecoder.prayertime.audit.model;

import io.github.cyonecoder.prayertime.audit.dataset.HabousDatasetSplit;
import io.github.cyonecoder.prayertime.audit.optimization.CityLocations;
import io.github.cyonecoder.prayertime.audit.optimization.HabousRawEngine;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset.Observation;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Shared helpers for the global-model inference tests. No network is ever used. */
final class GlobalModelFixtures {

    /** The committed multi-city reference snapshot used across the audit. */
    static final Path DATASET = Path.of("research-data/habous");

    /** A fixed freeze instant so provenance is deterministic in tests. */
    static final Instant FROZEN_AT = Instant.parse("2026-07-07T00:00:00Z");

    /** A fixed code commit label for deterministic provenance in tests. */
    static final String COMMIT = "test-commit";

    private GlobalModelFixtures() {
    }

    static ReferenceDataset realDataset() {
        return ReferenceDataset.load(DATASET);
    }

    static HabousDatasetSplit split() {
        return HabousDatasetSplit.load();
    }

    static GlobalModelSelection selection() {
        return new GlobalModelSelection(new HabousRawEngine(), new CityLocations(), split());
    }

    static Observation obs(
            String city, String date, String fajr, String sunrise,
            String dhuhr, String asr, String maghrib, String isha) {
        return new Observation(city, LocalDate.parse(date),
                LocalTime.parse(fajr), LocalTime.parse(sunrise), LocalTime.parse(dhuhr),
                LocalTime.parse(asr), LocalTime.parse(maghrib), LocalTime.parse(isha));
    }
}
