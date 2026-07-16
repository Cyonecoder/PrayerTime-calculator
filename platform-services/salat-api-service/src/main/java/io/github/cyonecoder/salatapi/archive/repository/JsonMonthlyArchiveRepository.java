package io.github.cyonecoder.salatapi.archive.repository;

import io.github.cyonecoder.salatapi.archive.io.AtomicJsonFileWriter;
import io.github.cyonecoder.salatapi.archive.model.GregorianMonthlyArchive;
import io.github.cyonecoder.salatapi.archive.model.MonthlyArchiveManifest;
import io.github.cyonecoder.salatapi.archive.model.MonthlyCitySchedule;
import io.github.cyonecoder.salatapi.common.checksum.Sha256Service;
import io.github.cyonecoder.salatapi.config.SalatApiProperties;
import io.github.cyonecoder.salatapi.habous.parser.HabousLiveHtmlParser;
import io.github.cyonecoder.salatapi.habous.parser.HabousVilleDirectoryParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * Filesystem implementation of {@link MonthlyArchiveRepository} (spec 15, 17, 19).
 *
 * <p>Under {@code archives/gregorian/YYYY/MM/} it writes the archive file, a
 * {@code manifest.json} describing it, and a {@code revisions/} directory holding
 * every earlier archive whose content differed. The manifest's
 * {@code archiveSha256} is taken over the exact bytes written, so it can be used
 * to detect a tampered or truncated archive.
 *
 * <p><strong>An archive is never overwritten silently.</strong> A daily sync
 * legitimately rewrites the month as data accrues; before doing so, if the
 * existing archive's content differs, it is copied into {@code revisions/} keyed
 * by its checksum. The record of each earlier published state is preserved.
 */
@Repository
public class JsonMonthlyArchiveRepository implements MonthlyArchiveRepository {

    private static final Logger log = LoggerFactory.getLogger(JsonMonthlyArchiveRepository.class);
    private static final String MANIFEST_FILE = "manifest.json";
    private static final int SHA_PREFIX_LENGTH = 12;

    private final SalatApiProperties properties;
    private final AtomicJsonFileWriter jsonFiles;
    private final Sha256Service sha256;

    public JsonMonthlyArchiveRepository(
            SalatApiProperties properties, AtomicJsonFileWriter jsonFiles, Sha256Service sha256) {
        this.properties = properties;
        this.jsonFiles = jsonFiles;
        this.sha256 = sha256;
    }

    @Override
    public MonthlyArchiveManifest save(
            GregorianMonthlyArchive archive, Instant lastSuccessfulSyncAt, List<String> warnings) {
        YearMonth month = archive.yearMonth();
        Path archiveFile = properties.archiveFile(month);

        String json = jsonFiles.toJson(archive);
        reviseExistingArchiveIfDifferent(month, archiveFile, json);
        jsonFiles.writeString(archiveFile, json);
        String archiveSha = sha256.hex(json);

        MonthlyArchiveManifest manifest = manifestFor(archive, archiveFile.getFileName().toString(),
                archiveSha, lastSuccessfulSyncAt, warnings);
        jsonFiles.write(properties.archiveManifestFile(month), manifest);
        return manifest;
    }

    @Override
    public Optional<GregorianMonthlyArchive> findArchive(YearMonth month) {
        Path file = properties.archiveFile(month);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(jsonFiles.read(file, GregorianMonthlyArchive.class));
    }

    @Override
    public Optional<MonthlyArchiveManifest> findManifest(YearMonth month) {
        Path file = properties.archiveManifestFile(month);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(jsonFiles.read(file, MonthlyArchiveManifest.class));
    }

    @Override
    public List<String> revisionChecksums(YearMonth month) {
        Path revisions = properties.archiveRevisionsDirectory(month);
        if (!Files.isDirectory(revisions)) {
            return List.of();
        }
        try (var entries = Files.list(revisions)) {
            return entries.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list revisions in " + revisions, e);
        }
    }

    private MonthlyArchiveManifest manifestFor(
            GregorianMonthlyArchive archive, String archiveFileName, String archiveSha,
            Instant lastSuccessfulSyncAt, List<String> warnings) {
        int expectedDays = archive.yearMonth().lengthOfMonth();
        List<MonthlyArchiveManifest.FailedCity> failed = new ArrayList<>();
        for (MonthlyCitySchedule city : archive.cities()) {
            if (city.isFailed()) {
                failed.add(new MonthlyArchiveManifest.FailedCity(
                        city.habousCityId(), city.officialNameAr(), city.failureCode()));
            }
        }
        return new MonthlyArchiveManifest(
                MonthlyArchiveManifest.SCHEMA_VERSION,
                archive.period(),
                archive.status(),
                archiveFileName,
                archiveSha,
                archive.officialCatalogCityCount(),
                archive.cityCount(),
                archive.completeCityCount(),
                archive.partialCityCount(),
                archive.failedCityCount(),
                expectedDays,
                lastSuccessfulSyncAt,
                HabousLiveHtmlParser.PARSER_VERSION,
                HabousVilleDirectoryParser.PARSER_VERSION,
                warnings == null ? List.of() : List.copyOf(warnings),
                List.copyOf(failed));
    }

    /** Copies an existing archive into {@code revisions/} when the new content differs. */
    private void reviseExistingArchiveIfDifferent(YearMonth month, Path archiveFile, String newJson) {
        if (!Files.isRegularFile(archiveFile)) {
            return;
        }
        try {
            String existing = Files.readString(archiveFile, StandardCharsets.UTF_8);
            String existingSha = sha256.hex(existing);
            if (existingSha.equals(sha256.hex(newJson))) {
                return; // identical content; nothing to revise
            }
            Path revisionDir = properties.archiveRevisionsDirectory(month).resolve(prefix(existingSha));
            Files.createDirectories(revisionDir);
            jsonFiles.writeString(revisionDir.resolve(archiveFile.getFileName().toString()), existing);
            Path existingManifest = properties.archiveManifestFile(month);
            if (Files.isRegularFile(existingManifest)) {
                jsonFiles.writeString(revisionDir.resolve(MANIFEST_FILE),
                        Files.readString(existingManifest, StandardCharsets.UTF_8));
            }
            log.info("Revised prior {} archive (checksum {}) before rewriting", month, prefix(existingSha));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to revise archive for " + month, e);
        }
    }

    private static String prefix(String checksum) {
        return checksum.substring(0, Math.min(SHA_PREFIX_LENGTH, checksum.length()));
    }
}
