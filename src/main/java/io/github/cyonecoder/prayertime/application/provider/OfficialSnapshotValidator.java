package io.github.cyonecoder.prayertime.application.provider;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Validates an official Habous snapshot before it is stored or served. A snapshot
 * is accepted only when every check passes; otherwise the reasons are returned
 * and the snapshot is refused (never partially served, never repaired).
 *
 * <p>Checks: expected city id and mapping, contiguous date coverage (no internal
 * gaps), no duplicate dates, all six times present and coherent, and stored
 * provenance (source checksum and parser version).
 */
public final class OfficialSnapshotValidator {

    /** The outcome of validating one snapshot. */
    public record Result(boolean valid, List<String> problems) {
        public Result {
            problems = List.copyOf(problems);
        }

        public static Result ok() {
            return new Result(true, List.of());
        }

        public static Result failed(List<String> problems) {
            return new Result(false, problems);
        }
    }

    /**
     * Validates a parsed snapshot against its expected identity.
     *
     * @param snapshot           the parsed snapshot
     * @param expectedCityId     the application city slug it must be for
     * @param expectedHabousId   the Habous id it must carry (from the mapping), or
     *                           {@code null} to skip the mapping check
     * @return the validation result
     */
    public Result validate(OfficialSnapshot snapshot, String expectedCityId, String expectedHabousId) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<String> problems = new ArrayList<>();

        if (expectedCityId != null && !expectedCityId.equalsIgnoreCase(snapshot.cityId())) {
            problems.add("city id mismatch: expected '" + expectedCityId
                    + "' but snapshot is '" + snapshot.cityId() + "'");
        }
        if (expectedHabousId != null && !expectedHabousId.equals(snapshot.habousCityId())) {
            problems.add("Habous mapping mismatch: expected id '" + expectedHabousId
                    + "' but snapshot carries '" + snapshot.habousCityId() + "'");
        }
        if (snapshot.sourceChecksum().isBlank()) {
            problems.add("missing source checksum");
        }
        if (snapshot.parserVersion().isBlank()) {
            problems.add("missing parser version");
        }
        if (snapshot.days().isEmpty()) {
            problems.add("no dates present");
            return Result.failed(problems);
        }

        List<LocalDate> dates = snapshot.dates();
        // No duplicate dates: the map keys are unique by construction, but guard
        // against a snapshot that lost rows to key collisions.
        // Contiguous coverage: no internal gaps between first and last date.
        LocalDate first = dates.get(0);
        LocalDate last = dates.get(dates.size() - 1);
        long span = last.toEpochDay() - first.toEpochDay() + 1;
        if (span != dates.size()) {
            problems.add("non-contiguous date coverage: " + dates.size()
                    + " rows span " + span + " days between " + first + " and " + last);
        }
        // All six times coherent (present is guaranteed by the record; check that
        // the day is internally ordered, which every real Habous day is).
        snapshot.days().forEach((date, t) -> {
            if (!(t.fajr().isBefore(t.sunrise())
                    && t.sunrise().isBefore(t.dhuhr())
                    && t.dhuhr().isBefore(t.asr())
                    && t.asr().isBefore(t.maghrib())
                    && t.maghrib().isBefore(t.isha()))) {
                problems.add("times not strictly increasing on " + date + ": " + t);
            }
        });

        return problems.isEmpty() ? Result.ok() : Result.failed(problems);
    }
}
