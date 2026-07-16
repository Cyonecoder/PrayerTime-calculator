package io.github.cyonecoder.salatapi.archive.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The {@code manifest.json} beside a monthly archive: what it contains, how
 * complete it is, and which official sources it was assembled from.
 *
 * <p>The manifest exists so completeness is answerable without parsing the whole
 * archive, and so an archive's provenance is auditable — every contributing source
 * checksum is listed, and those checksums resolve to immutable raw snapshots.
 *
 * @param year                the Gregorian year
 * @param month               the Gregorian month, 1..12
 * @param generatedAt         when the archive was last assembled
 * @param status              overall completeness
 * @param cityCount           how many cities have at least one day
 * @param completeCityCount   how many cities have every day of the month
 * @param expectedDayCount    the number of calendar days in the month
 * @param archiveChecksum     SHA-256 of the serialized archive file
 * @param sourceChecksums     every raw source page that contributed, sorted
 * @param incompleteCities    ville ids still missing at least one day
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MonthlyArchiveManifest(
        int year,
        int month,
        Instant generatedAt,
        MonthlyArchiveStatus status,
        int cityCount,
        long completeCityCount,
        int expectedDayCount,
        String archiveChecksum,
        List<String> sourceChecksums,
        List<String> incompleteCities) {

    public MonthlyArchiveManifest {
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(status, "status");
        sourceChecksums = sourceChecksums == null ? List.of() : List.copyOf(sourceChecksums);
        incompleteCities = incompleteCities == null ? List.of() : List.copyOf(incompleteCities);
    }
}
