package io.github.cyonecoder.salatapi.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The operational status of the service and its data (spec 23.15).
 *
 * @param schedulerEnabled        whether the daily scheduler is active
 * @param syncInProgress          whether a run is happening right now
 * @param officialCatalogueCount  observed official city count
 * @param latestCatalogueRefresh  when the catalogue was last refreshed
 * @param latestSuccessfulSync    the last run that stored data
 * @param latestFailedSync        the last run that failed or had failed cities
 * @param monthlyArchiveStatuses  status per archived month, {@code period -> status}
 * @param officialDataFreshnessDays age in days of the most recent official date, or null
 * @param secApproachAvailable    whether the sec-approach engine is serving
 * @param secApproachCityCount    how many cities sec-approach supports (48)
 * @param latestVerification      the most recent verification metrics summary
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DataStatusResponse(
        boolean schedulerEnabled,
        boolean syncInProgress,
        int officialCatalogueCount,
        Instant latestCatalogueRefresh,
        LocalDate latestSuccessfulSync,
        LocalDate latestFailedSync,
        Map<String, String> monthlyArchiveStatuses,
        Long officialDataFreshnessDays,
        boolean secApproachAvailable,
        int secApproachCityCount,
        LatestVerification latestVerification) {

    /**
     * @param date            the verified date
     * @param comparedCityCount cities compared
     * @param exactCityCount    cities agreeing exactly
     * @param exactRatio        overall exact-minute ratio
     * @param withinOneRatio    overall within-one-minute ratio
     */
    public record LatestVerification(
            LocalDate date, int comparedCityCount, int exactCityCount,
            double exactRatio, double withinOneRatio) {
    }
}
