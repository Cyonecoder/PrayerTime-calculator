package io.github.cyonecoder.salatapi.archive.repository;

import io.github.cyonecoder.salatapi.archive.io.AtomicJsonFileWriter;
import io.github.cyonecoder.salatapi.common.checksum.Sha256Service;
import io.github.cyonecoder.salatapi.config.SalatApiProperties;
import io.github.cyonecoder.salatapi.habous.model.HabousPageSnapshot;
import io.github.cyonecoder.salatapi.habous.model.HabousPublishedDay;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * Stores immutable raw official page snapshots under
 * {@code data/raw/habous/YYYY/MM/DD/{habousCityId}/} as {@code source.html},
 * {@code source.sha256}, {@code parsed-days.json} and {@code metadata.json}
 * (spec 15).
 *
 * <p><strong>A primary snapshot is never overwritten.</strong> When an incoming
 * source has the same checksum as the stored one it is a duplicate and nothing is
 * rewritten. When the checksum differs, the new source is written as a
 * <em>revision</em> under {@code revisions/{sha-prefix}/} and the original is left
 * untouched.
 *
 * <p>This matters more here than in an ordinary cache. The official source has no
 * month parameter, so a page that is overwritten is gone forever — it cannot be
 * re-fetched at any price. These files are the only historical record that
 * exists.
 *
 * <p>Ported from {@code audit.dataset.HabousSnapshotStore}, re-pathed to the
 * runtime layout; see {@code docs/EXTRACTION_MAP.md}.
 */
@Repository
public class RawHabousSnapshotRepository {

    private static final Logger log = LoggerFactory.getLogger(RawHabousSnapshotRepository.class);

    private static final String SOURCE_HTML = "source.html";
    private static final String SOURCE_SHA = "source.sha256";
    private static final String PARSED_DAYS = "parsed-days.json";
    private static final String METADATA_JSON = "metadata.json";
    private static final String REVISIONS_DIR = "revisions";
    private static final int SHA_PREFIX_LENGTH = 12;

    /** What happened when a snapshot was saved. */
    public enum SaveStatus {
        /** No snapshot existed for this city/day; a new primary was created. */
        CREATED,
        /** An identical checksum already existed; nothing was rewritten. */
        DUPLICATE,
        /** The primary checksum differed; the source was saved as a revision. */
        REVISION_SAVED
    }

    /**
     * @param status   what happened
     * @param location the directory the source was written to
     * @param checksum the SHA-256 of the incoming source
     */
    public record SaveResult(SaveStatus status, Path location, String checksum) {

        /** @return whether the incoming source is now stored (as primary or revision) */
        public boolean isStored() {
            return status == SaveStatus.CREATED || status == SaveStatus.REVISION_SAVED;
        }
    }

    private final SalatApiProperties properties;
    private final Sha256Service sha256;
    private final AtomicJsonFileWriter jsonFiles;

    public RawHabousSnapshotRepository(
            SalatApiProperties properties, Sha256Service sha256, AtomicJsonFileWriter jsonFiles) {
        this.properties = properties;
        this.sha256 = sha256;
        this.jsonFiles = jsonFiles;
    }

    /**
     * Saves one fetched page.
     *
     * @param html     the raw HTML exactly as served
     * @param days     the days parsed from it
     * @param metadata the provenance to record
     * @return what happened, and where the source landed
     */
    public SaveResult save(String html, List<HabousPublishedDay> days, HabousPageSnapshot metadata) {
        Objects.requireNonNull(html, "html");
        Objects.requireNonNull(metadata, "metadata");
        String checksum = sha256.hex(html);

        Path directory = properties.rawSnapshotDirectory(metadata.fetchedOn(), metadata.habousCityId());
        Optional<String> existing = storedChecksum(directory);

        if (existing.isEmpty()) {
            writeSnapshot(directory, html, checksum, days, metadata);
            return new SaveResult(SaveStatus.CREATED, directory, checksum);
        }
        if (existing.get().equals(checksum)) {
            // Identical content: the site republished nothing. Rewriting would
            // only churn mtimes and risk corrupting a good file for no gain.
            return new SaveResult(SaveStatus.DUPLICATE, directory, checksum);
        }

        // The source changed for a day we already captured. The previous source
        // stays exactly where it is; the new one becomes a revision.
        Path revision = directory.resolve(REVISIONS_DIR).resolve(shaPrefix(checksum));
        writeSnapshot(revision, html, checksum, days, metadata);
        log.warn("Official source for city {} changed on {} (stored {}, incoming {}); saved as revision {}",
                metadata.habousCityId(), metadata.fetchedOn(),
                shaPrefix(existing.get()), shaPrefix(checksum), revision);
        return new SaveResult(SaveStatus.REVISION_SAVED, revision, checksum);
    }

    /**
     * @param fetchedOn    the fetch day
     * @param habousCityId the official ville id
     * @return the stored primary metadata, if a snapshot exists
     */
    public Optional<HabousPageSnapshot> findMetadata(LocalDate fetchedOn, String habousCityId) {
        Path file = properties.rawSnapshotDirectory(fetchedOn, habousCityId).resolve(METADATA_JSON);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(jsonFiles.read(file, HabousPageSnapshot.class));
    }

    /**
     * @param fetchedOn    the fetch day
     * @param habousCityId the official ville id
     * @return the days parsed from the stored primary snapshot, or empty if none
     */
    public Optional<List<HabousPublishedDay>> findDays(LocalDate fetchedOn, String habousCityId) {
        Path file = properties.rawSnapshotDirectory(fetchedOn, habousCityId).resolve(PARSED_DAYS);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(List.of(jsonFiles.read(file, HabousPublishedDay[].class)));
    }

    /** @return the raw HTML of the stored primary snapshot, if present */
    public Optional<String> findRawHtml(LocalDate fetchedOn, String habousCityId) {
        Path file = properties.rawSnapshotDirectory(fetchedOn, habousCityId).resolve(SOURCE_HTML);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

    /** @return whether a primary snapshot already exists for this city/day */
    public boolean exists(LocalDate fetchedOn, String habousCityId) {
        return storedChecksum(properties.rawSnapshotDirectory(fetchedOn, habousCityId)).isPresent();
    }

    /** One stored primary snapshot: its provenance and parsed days. */
    public record StoredSnapshot(HabousPageSnapshot metadata, List<HabousPublishedDay> days) {}

    /**
     * Enumerates every stored <strong>primary</strong> snapshot (revisions
     * excluded), for rebuilding archives without re-fetching (spec 25).
     *
     * @return all primary snapshots on disk, in filesystem order
     */
    public List<StoredSnapshot> listAllPrimary() {
        Path root = properties.rawDirectory();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var paths = Files.walk(root)) {
            return paths
                    .filter(p -> p.getFileName().toString().equals(METADATA_JSON))
                    // A revision's metadata lives under .../{ville}/revisions/{sha}/; skip those.
                    .filter(p -> !p.toString().contains(java.io.File.separator + REVISIONS_DIR
                            + java.io.File.separator))
                    .map(this::readSnapshot)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk raw snapshots under " + root, e);
        }
    }

    private StoredSnapshot readSnapshot(Path metadataFile) {
        Path dir = metadataFile.getParent();
        Path daysFile = dir.resolve(PARSED_DAYS);
        if (!Files.isRegularFile(daysFile)) {
            return null;
        }
        HabousPageSnapshot metadata = jsonFiles.read(metadataFile, HabousPageSnapshot.class);
        List<HabousPublishedDay> days = List.of(jsonFiles.read(daysFile, HabousPublishedDay[].class));
        return new StoredSnapshot(metadata, days);
    }

    /** @return the checksums of every revision stored for this city/day */
    public List<String> revisionChecksums(LocalDate fetchedOn, String habousCityId) {
        Path revisions = properties.rawSnapshotDirectory(fetchedOn, habousCityId).resolve(REVISIONS_DIR);
        if (!Files.isDirectory(revisions)) {
            return List.of();
        }
        try (var entries = Files.list(revisions)) {
            return entries.filter(Files::isDirectory)
                    .map(p -> storedChecksum(p).orElse(null))
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list revisions in " + revisions, e);
        }
    }

    private Optional<String> storedChecksum(Path directory) {
        Path file = directory.resolve(SOURCE_SHA);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

    private void writeSnapshot(
            Path directory, String html, String checksum,
            List<HabousPublishedDay> days, HabousPageSnapshot metadata) {
        try {
            Files.createDirectories(directory);
            // The HTML lands before its checksum, so a crash between the two
            // leaves a directory with no sha and is treated as absent rather than
            // as a valid snapshot with a wrong digest.
            Files.writeString(directory.resolve(SOURCE_HTML), html, StandardCharsets.UTF_8);
            jsonFiles.write(directory.resolve(PARSED_DAYS), days);
            jsonFiles.write(directory.resolve(METADATA_JSON), metadata);
            Files.writeString(directory.resolve(SOURCE_SHA), checksum, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write snapshot to " + directory, e);
        }
    }

    private static String shaPrefix(String checksum) {
        return checksum.substring(0, Math.min(SHA_PREFIX_LENGTH, checksum.length()));
    }
}
