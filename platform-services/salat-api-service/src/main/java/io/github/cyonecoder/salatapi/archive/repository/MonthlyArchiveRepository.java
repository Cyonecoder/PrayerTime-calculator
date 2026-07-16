package io.github.cyonecoder.salatapi.archive.repository;

import io.github.cyonecoder.salatapi.archive.model.GregorianMonthlyArchive;
import io.github.cyonecoder.salatapi.archive.model.MonthlyArchiveManifest;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Persists assembled Gregorian monthly archives and their manifests.
 *
 * <p>An interface so the filesystem layout can be swapped later (spec 4 forbids a
 * database, but the storage boundary is still worth naming).
 */
public interface MonthlyArchiveRepository {

    /**
     * Writes an archive and its manifest, revisioning any prior archive whose
     * content differs.
     *
     * @param archive               the assembled archive
     * @param lastSuccessfulSyncAt  when a sync last stored data for this month
     * @param warnings              notices to record on the manifest
     * @return the manifest that was written
     */
    MonthlyArchiveManifest save(
            GregorianMonthlyArchive archive, Instant lastSuccessfulSyncAt, List<String> warnings);

    /** @param month the Gregorian month */
    Optional<GregorianMonthlyArchive> findArchive(YearMonth month);

    /** @param month the Gregorian month */
    Optional<MonthlyArchiveManifest> findManifest(YearMonth month);

    /** @param month the Gregorian month */
    List<String> revisionChecksums(YearMonth month);
}
