package io.github.cyonecoder.salatapi.verification.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * One sec-approach city compared against official data for one date.
 *
 * <p>Only the six prayers are compared; {@code exactMatch} is true when every one
 * agrees to the minute. This is a comparison, not a merge: the two sources are
 * never blended.
 *
 * @param habousCityId      the official ville id
 * @param applicationCityId the application slug (always present for a sec-approach city)
 * @param date              the date compared
 * @param differences       the per-prayer differences
 * @param exactMatch        whether all six prayers agree exactly
 * @param maxAbsDeltaMinutes the largest absolute per-prayer difference
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CityDailyVerification(
        String habousCityId,
        String applicationCityId,
        LocalDate date,
        List<PrayerDifference> differences,
        boolean exactMatch,
        int maxAbsDeltaMinutes) {

    public CityDailyVerification {
        Objects.requireNonNull(habousCityId, "habousCityId");
        Objects.requireNonNull(date, "date");
        differences = differences == null ? List.of() : List.copyOf(differences);
    }

    /**
     * @param habousCityId      the ville id
     * @param applicationCityId the application slug
     * @param date              the date
     * @param differences       the per-prayer differences
     * @return the assembled verification with its aggregates computed
     */
    public static CityDailyVerification of(
            String habousCityId, String applicationCityId, LocalDate date,
            List<PrayerDifference> differences) {
        boolean exact = differences.stream().allMatch(PrayerDifference::exact);
        int maxAbs = differences.stream().mapToInt(d -> Math.abs(d.deltaMinutes())).max().orElse(0);
        return new CityDailyVerification(habousCityId, applicationCityId, date, differences, exact, maxAbs);
    }
}
