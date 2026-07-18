package io.github.cyonecoder.salatapi.archive.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * One city's entry in a Gregorian monthly archive (spec 17).
 *
 * <p>Every city in the official catalogue appears, including ones whose
 * collection failed — those carry {@link CityArchiveStatus#UNAVAILABLE}, a
 * {@code failureCode}, and no days. Omitting them would make a failure
 * indistinguishable from a city that was never official.
 *
 * <p>The identity is the official ville id (spec 13). {@code applicationCityId} is
 * null for the villes with no verified application mapping, and
 * {@code secApproachSupported} is false for every city outside the trained 48 —
 * collecting official data for a city never makes it a sec-approach city.
 *
 * @param habousCityId         the official ville id — the stable archive identity
 * @param officialNameAr       the exact Arabic name as published
 * @param applicationCityId    the application slug, or {@code null} for official-only cities
 * @param secApproachSupported whether this city has accepted sec-approach offsets
 * @param status               completeness of this city's month
 * @param daysExpected         calendar days in the month
 * @param daysAvailable        how many are published
 * @param days                 the published days, unique by date and ascending
 * @param failureCode          why there are no usable days; null unless {@code UNAVAILABLE}
 * @param sourceChecksums      every source page contributing days here, sorted
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MonthlyCitySchedule(
        String habousCityId,
        String officialNameAr,
        String applicationCityId,
        boolean secApproachSupported,
        CityArchiveStatus status,
        int daysExpected,
        int daysAvailable,
        List<ArchivedDay> days,
        ArchiveFailureCode failureCode,
        List<String> sourceChecksums) {

    public MonthlyCitySchedule {
        Objects.requireNonNull(habousCityId, "habousCityId");
        Objects.requireNonNull(status, "status");
        days = days == null ? List.of() : List.copyOf(days);
        sourceChecksums = sourceChecksums == null ? List.of() : List.copyOf(sourceChecksums);
    }

    /**
     * @param month the Gregorian month
     * @return the calendar days with no published entry, ascending; empty when complete
     */
    @JsonIgnore
    public List<LocalDate> missingDays(YearMonth month) {
        Set<LocalDate> present = new TreeSet<>();
        days.forEach(day -> present.add(day.date()));
        List<LocalDate> missing = new ArrayList<>();
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate date = month.atDay(day);
            if (!present.contains(date)) {
                missing.add(date);
            }
        }
        return List.copyOf(missing);
    }

    /** @return whether this city's month is fully published */
    @JsonIgnore
    public boolean isComplete() {
        return status == CityArchiveStatus.COMPLETE;
    }

    /** @return whether collection failed for this city */
    @JsonIgnore
    public boolean isFailed() {
        return status == CityArchiveStatus.UNAVAILABLE;
    }
}
