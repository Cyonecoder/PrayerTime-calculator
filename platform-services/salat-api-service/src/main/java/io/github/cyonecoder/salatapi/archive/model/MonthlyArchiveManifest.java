package io.github.cyonecoder.salatapi.archive.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The {@code manifest.json} beside a monthly archive (spec 19): a compact summary
 * of what the archive contains and how trustworthy it is, so completeness and
 * integrity are answerable without parsing the whole archive file.
 *
 * <p>{@code archiveSha256} pins the exact archive file the manifest describes, so
 * a tampered or truncated archive is detectable. Record component order is the
 * serialized field order, matching the documented schema.
 *
 * @param schemaVersion             the manifest schema version
 * @param period                    the Gregorian month, {@code YYYY-MM}
 * @param status                    overall archive completeness
 * @param archiveFile               the archive file this manifest sits beside
 * @param archiveSha256             SHA-256 of that archive file, byte-for-byte
 * @param officialCatalogCityCount  how many cities the official catalogue currently lists
 * @param archiveCityCount          how many cities the archive lists (failures included)
 * @param completeCityCount         cities with every day of the month
 * @param partialCityCount          cities with some but not all days
 * @param unavailableCityCount      cities with no usable days (a failure code)
 * @param expectedDaysPerCompleteCity calendar days in the month
 * @param lastSuccessfulSyncAt      when a sync last stored data for this month
 * @param parserVersion             the timetable parser version
 * @param villeParserVersion        the ville-directory parser version
 * @param warnings                  human-readable notices (catalogue change, anomalies)
 * @param failedCities              the cities with no usable days, with their failure codes
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MonthlyArchiveManifest(
        String schemaVersion,
        String period,
        MonthlyArchiveStatus status,
        String archiveFile,
        String archiveSha256,
        int officialCatalogCityCount,
        int archiveCityCount,
        long completeCityCount,
        long partialCityCount,
        long unavailableCityCount,
        int expectedDaysPerCompleteCity,
        Instant lastSuccessfulSyncAt,
        String parserVersion,
        String villeParserVersion,
        List<String> warnings,
        List<FailedCity> failedCities) {

    /** The current manifest schema version. */
    public static final String SCHEMA_VERSION = "1.0";

    /**
     * One city that produced no usable schedule this month.
     *
     * @param habousCityId   the official ville id
     * @param officialNameAr the exact Arabic name
     * @param failureCode    why it has no days
     */
    public record FailedCity(String habousCityId, String officialNameAr, ArchiveFailureCode failureCode) {}

    public MonthlyArchiveManifest {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(archiveFile, "archiveFile");
        Objects.requireNonNull(archiveSha256, "archiveSha256");
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        failedCities = failedCities == null ? List.of() : List.copyOf(failedCities);
    }
}
