package io.github.cyonecoder.salatapi.archive.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One monthly JSON file holding the state of every official Habous city for one
 * <strong>Gregorian</strong> month (spec 17).
 *
 * <p>Rows are placed by their own resolved Gregorian date, never by the month the
 * page was fetched in, so a single Hijri source page contributes to two archives.
 *
 * <p>Every city in the official catalogue is listed — including ones whose
 * collection failed. The counts are real: {@code completeCityCount +
 * partialCityCount + failedCityCount == cityCount}.
 *
 * <p>Record component order is the serialized field order, so it deliberately
 * matches the documented schema.
 *
 * @param schemaVersion            the archive schema version
 * @param source                   always {@code HABOUS_OFFICIAL} — these are published times, never calculated
 * @param period                   the Gregorian month, {@code YYYY-MM}
 * @param periodType               always {@code GREGORIAN_MONTH}, to distinguish it from the Hijri source
 * @param timezone                 the civil zone the times are published in
 * @param status                   overall completeness
 * @param generatedAt              when this archive was first assembled
 * @param lastUpdatedAt            when it was last reassembled
 * @param officialCatalogCityCount how many cities the official catalogue currently lists
 * @param cityCount                how many cities appear here (failures included)
 * @param completeCityCount        cities with every day of the month
 * @param partialCityCount         cities with some days
 * @param failedCityCount          cities with a failure code and no days
 * @param cities                   every official city, sorted by numeric ville id
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GregorianMonthlyArchive(
        String schemaVersion,
        String source,
        String period,
        String periodType,
        String timezone,
        MonthlyArchiveStatus status,
        Instant generatedAt,
        Instant lastUpdatedAt,
        int officialCatalogCityCount,
        int cityCount,
        long completeCityCount,
        long partialCityCount,
        long failedCityCount,
        List<MonthlyCitySchedule> cities) {

    /** The current archive schema version. */
    public static final String SCHEMA_VERSION = "1.0";
    /** These are official published times, never calculated ones. */
    public static final String SOURCE_OFFICIAL = "HABOUS_OFFICIAL";
    /** The archive is Gregorian even though the source timetable is Hijri. */
    public static final String PERIOD_TYPE_GREGORIAN_MONTH = "GREGORIAN_MONTH";
    /** Every Moroccan prayer time is published in this civil zone. */
    public static final String TIMEZONE = "Africa/Casablanca";

    public GregorianMonthlyArchive {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(lastUpdatedAt, "lastUpdatedAt");
        cities = cities == null ? List.of() : List.copyOf(cities);
    }

    /** @return the Gregorian month this archive covers */
    @JsonIgnore
    public YearMonth yearMonth() {
        return YearMonth.parse(period);
    }

    /** @param habousCityId the official ville id */
    public Optional<MonthlyCitySchedule> city(String habousCityId) {
        if (habousCityId == null) {
            return Optional.empty();
        }
        return cities.stream()
                .filter(c -> c.habousCityId().equals(habousCityId.trim()))
                .findFirst();
    }

    /** @return only the cities that can be compared against sec-approach */
    @JsonIgnore
    public List<MonthlyCitySchedule> secApproachCities() {
        return cities.stream().filter(MonthlyCitySchedule::secApproachSupported).toList();
    }
}
