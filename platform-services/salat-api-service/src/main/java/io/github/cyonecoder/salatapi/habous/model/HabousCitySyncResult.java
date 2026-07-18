package io.github.cyonecoder.salatapi.habous.model;

import io.github.cyonecoder.salatapi.archive.model.ArchiveFailureCode;
import java.util.Optional;

/**
 * The outcome of collecting one official city in a sync run (spec 20).
 *
 * <p>Because a failure for one city must never erase valid data for the others,
 * each city's collection produces one of these independently. A failure carries a
 * {@link ArchiveFailureCode} so the archive can list the city as unavailable
 * rather than omit it.
 *
 * @param habousCityId the official ville id
 * @param status       what happened
 * @param failureCode  the archive-level reason, when this is a failure
 * @param detail       a human-readable note
 */
public record HabousCitySyncResult(
        String habousCityId,
        Status status,
        ArchiveFailureCode failureCode,
        String detail) {

    /** The category of a per-city outcome. */
    public enum Status {
        /** A new raw snapshot was stored. */
        COLLECTED,
        /** The page was identical to one already stored today; nothing rewritten. */
        UNCHANGED,
        /** The source changed for a page already captured today; stored as a revision. */
        REVISED,
        /** The city could not be fetched (outage, timeout, non-200). */
        FETCH_FAILED,
        /** A page arrived but was not a trustworthy timetable. */
        INVALID_PAGE,
        /** The page was for a different city than requested. */
        WRONG_CITY
    }

    /** @return whether usable data was stored (as primary, duplicate or revision) */
    public boolean isStored() {
        return status == Status.COLLECTED || status == Status.UNCHANGED || status == Status.REVISED;
    }

    /** @return the archive failure code, when this outcome is a failure */
    public Optional<ArchiveFailureCode> archiveFailureCode() {
        return Optional.ofNullable(failureCode);
    }

    /** @param habousCityId the ville id */
    public static HabousCitySyncResult collected(String habousCityId, HabousCitySyncResult.Status status) {
        return new HabousCitySyncResult(habousCityId, status, null, null);
    }

    /**
     * @param habousCityId the ville id
     * @param status       the failure category
     * @param failureCode  the archive-level code
     * @param detail       a note
     */
    public static HabousCitySyncResult failed(
            String habousCityId, Status status, ArchiveFailureCode failureCode, String detail) {
        return new HabousCitySyncResult(habousCityId, status, failureCode, detail);
    }
}
