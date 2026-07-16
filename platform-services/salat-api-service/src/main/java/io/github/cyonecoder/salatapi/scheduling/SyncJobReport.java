package io.github.cyonecoder.salatapi.scheduling;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The record of one daily synchronization run (spec 20).
 *
 * <p>Stored under {@code jobs/} so every run is auditable: what the catalogue
 * looked like, how many cities were collected versus failed, which archives were
 * republished, and whether verification ran. A failed run still produces a report
 * — silence is not an acceptable outcome for a scheduled job.
 *
 * @param runDate            the Moroccan civil date the run belongs to
 * @param startedAt          when the run began
 * @param finishedAt         when it ended
 * @param outcome            the overall result
 * @param officialCityCount  how many official cities the catalogue listed
 * @param collectedCityCount cities whose data was stored (new, unchanged or revised)
 * @param failedCityCount    cities that could not be collected
 * @param catalogueChanged   whether the official catalogue changed during the run
 * @param publishedMonths    the Gregorian months republished, {@code YYYY-MM}
 * @param verificationRun    whether the sec-approach comparison ran
 * @param failedCities       ville ids that failed, with a short reason
 * @param warnings           notices worth surfacing
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SyncJobReport(
        LocalDate runDate,
        Instant startedAt,
        Instant finishedAt,
        Outcome outcome,
        int officialCityCount,
        int collectedCityCount,
        int failedCityCount,
        boolean catalogueChanged,
        List<String> publishedMonths,
        boolean verificationRun,
        List<FailedCity> failedCities,
        List<String> warnings) {

    /** The overall result of a run. */
    public enum Outcome {
        /** Every city collected. */
        SUCCESS,
        /** The run completed, but at least one city failed. */
        PARTIAL_SUCCESS,
        /** The run could not start (lock held) or aborted before publishing. */
        SKIPPED,
        /** The run threw before completing. */
        FAILED
    }

    /**
     * @param habousCityId the ville id
     * @param reason       a short human-readable reason
     */
    public record FailedCity(String habousCityId, String reason) {}

    public SyncJobReport {
        Objects.requireNonNull(runDate, "runDate");
        Objects.requireNonNull(outcome, "outcome");
        publishedMonths = publishedMonths == null ? List.of() : List.copyOf(publishedMonths);
        failedCities = failedCities == null ? List.of() : List.copyOf(failedCities);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** @return the run's wall-clock duration, when it finished */
    public Duration duration() {
        return startedAt != null && finishedAt != null
                ? Duration.between(startedAt, finishedAt)
                : Duration.ZERO;
    }
}
