package io.github.cyonecoder.salatapi.archive.service;

import io.github.cyonecoder.salatapi.archive.model.ArchiveFailureCode;
import io.github.cyonecoder.salatapi.archive.model.GregorianMonthlyArchive;
import io.github.cyonecoder.salatapi.archive.model.MonthlyArchiveManifest;
import io.github.cyonecoder.salatapi.archive.repository.MonthlyArchiveRepository;
import io.github.cyonecoder.salatapi.archive.service.GregorianArchiveAssembler.ObservedPage;
import io.github.cyonecoder.salatapi.habous.model.CityCatalogueEntry;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Assembles the Gregorian months a collection touched and publishes them
 * atomically (spec 20).
 *
 * <p>Because one Hijri page spans two Gregorian months, a single collection run
 * typically republishes two archives. Each is assembled against the full official
 * catalogue (so every city appears) and written through the repository, which
 * revisions any prior state and writes the archive and manifest atomically.
 *
 * <p>Merging into an existing archive preserves its original {@code generatedAt}:
 * publishing is idempotent in identity, only {@code lastUpdatedAt} advances.
 */
@Service
public class MonthlyArchiveService {

    private static final Logger log = LoggerFactory.getLogger(MonthlyArchiveService.class);

    private final GregorianArchiveAssembler assembler;
    private final MonthlyArchiveRepository archives;

    public MonthlyArchiveService(
            GregorianArchiveAssembler assembler, MonthlyArchiveRepository archives) {
        this.assembler = assembler;
        this.archives = archives;
    }

    /**
     * Publishes every Gregorian month the given pages contribute to.
     *
     * @param pages                the pages collected this run
     * @param officialCities       the full official catalogue — every one appears in each archive
     * @param failures             per-city archive failure codes, keyed by ville id
     * @param lastSuccessfulSyncAt when this run stored data
     * @param catalogueChanged     whether the catalogue changed during collection (degrades months)
     * @param warnings             notices to record on each manifest
     * @return the manifests that were written, one per month
     */
    public List<MonthlyArchiveManifest> publish(
            List<ObservedPage> pages,
            List<CityCatalogueEntry> officialCities,
            Map<String, ArchiveFailureCode> failures,
            Instant lastSuccessfulSyncAt,
            boolean catalogueChanged,
            List<String> warnings) {

        List<YearMonth> months = assembler.monthsCovered(pages);
        List<MonthlyArchiveManifest> manifests = new ArrayList<>();

        for (YearMonth month : months) {
            Instant originalGeneratedAt = archives.findArchive(month)
                    .map(GregorianMonthlyArchive::generatedAt)
                    .orElse(null);

            GregorianMonthlyArchive archive = assembler.assemble(
                    month, pages, officialCities, failures, originalGeneratedAt, catalogueChanged);
            MonthlyArchiveManifest manifest =
                    archives.save(archive, lastSuccessfulSyncAt, warnings);
            manifests.add(manifest);
            log.info("Published archive {} — status {}, {} cities ({} complete, {} partial, {} unavailable)",
                    month, manifest.status(), manifest.archiveCityCount(), manifest.completeCityCount(),
                    manifest.partialCityCount(), manifest.unavailableCityCount());
        }
        return manifests;
    }

    /** @param month the Gregorian month */
    public Optional<GregorianMonthlyArchive> find(YearMonth month) {
        return archives.findArchive(month);
    }

    /** @param month the Gregorian month */
    public Optional<MonthlyArchiveManifest> findManifest(YearMonth month) {
        return archives.findManifest(month);
    }
}
