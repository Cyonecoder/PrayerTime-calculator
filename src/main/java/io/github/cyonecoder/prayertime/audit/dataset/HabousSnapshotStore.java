package io.github.cyonecoder.prayertime.audit.dataset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads and writes immutable Habous month snapshots under
 * {@code {root}/{cityId}/{year}/{month}/} as {@code source.html},
 * {@code source.sha256}, {@code prayer-times.csv} and {@code metadata.json}.
 *
 * <p>A primary snapshot is <strong>never overwritten</strong> when the incoming
 * source checksum differs from the stored one: the changed source is written
 * separately as a revision under {@code {month}/revisions/{sha-prefix}/}. An
 * identical checksum is reported as a duplicate and nothing is rewritten.
 */
public final class HabousSnapshotStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final String SOURCE_HTML = "source.html";
    private static final String SOURCE_SHA = "source.sha256";
    private static final String PRAYER_CSV = "prayer-times.csv";
    private static final String METADATA_JSON = "metadata.json";
    private static final String REVISIONS_DIR = "revisions";

    /** What happened when a snapshot was saved. */
    public enum SaveStatus {
        /** A new primary snapshot was created. */
        CREATED,
        /** An identical checksum already existed; nothing was rewritten. */
        DUPLICATE,
        /** The primary checksum differed; the source was saved as a revision. */
        REVISION_SAVED
    }

    /**
     * The result of a save attempt.
     *
     * @param status   what happened
     * @param location the directory written (or the existing primary on duplicate)
     * @param checksum the incoming source checksum
     */
    public record SaveResult(SaveStatus status, Path location, String checksum) {
    }

    /**
     * A stored snapshot read back from disk.
     *
     * @param directory the snapshot directory
     * @param month     the reconstructed month (provenance + days)
     * @param checksum  the stored source checksum
     */
    public record StoredSnapshot(Path directory, HabousReferenceMonth month, String checksum) {
    }

    private final Path root;

    public HabousSnapshotStore(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /** @return the SHA-256 hex checksum of the given text (UTF-8). */
    public static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** @return the primary directory for a city/year/month (may not exist). */
    public Path primaryDirectory(String cityId, int year, int month) {
        return root.resolve(cityId)
                .resolve(String.format(Locale.ROOT, "%04d", year))
                .resolve(String.format(Locale.ROOT, "%02d", month));
    }

    /** @return whether a primary snapshot already exists for the city/year/month. */
    public boolean primaryExists(String cityId, int year, int month) {
        return Files.isRegularFile(primaryDirectory(cityId, year, month).resolve(METADATA_JSON));
    }

    /**
     * Reads the primary snapshot for a city/year/month, if present.
     *
     * @param cityId the application city slug
     * @param year   the year
     * @param month  the month
     * @return the stored snapshot, or empty
     */
    public Optional<StoredSnapshot> readPrimary(String cityId, int year, int month) {
        Path dir = primaryDirectory(cityId, year, month);
        if (!Files.isRegularFile(dir.resolve(METADATA_JSON))) {
            return Optional.empty();
        }
        return Optional.of(read(dir));
    }

    /**
     * Scans the whole store root and reads every snapshot (primary and
     * revisions) it can find.
     *
     * @return the stored snapshots, ordered by directory path (empty if the root
     *         does not exist)
     */
    public List<StoredSnapshot> scanAll() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<StoredSnapshot> found = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            List<Path> metadataFiles = walk
                    .filter(p -> p.getFileName().toString().equals(METADATA_JSON))
                    .sorted()
                    .toList();
            for (Path metadata : metadataFiles) {
                found.add(read(metadata.getParent()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan snapshot root " + root, e);
        }
        return found;
    }

    /** @return whether the given snapshot directory is a saved revision (not the primary). */
    public static boolean isRevision(Path directory) {
        Path parent = directory.getParent();
        return parent != null && parent.getFileName().toString().equals(REVISIONS_DIR);
    }

    /**
     * Saves a snapshot immutably.
     *
     * @param month      the normalized month
     * @param sourceHtml the raw source HTML the month was parsed from
     * @return the outcome
     */
    public SaveResult save(HabousReferenceMonth month, String sourceHtml) {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(sourceHtml, "sourceHtml");
        String checksum = sha256Hex(sourceHtml);

        Path primary = primaryDirectory(month.applicationCityId(), month.year(), month.month());
        try {
            if (Files.isRegularFile(primary.resolve(SOURCE_SHA))) {
                String existing = Files.readString(primary.resolve(SOURCE_SHA), StandardCharsets.UTF_8).trim();
                if (existing.equals(checksum)) {
                    return new SaveResult(SaveStatus.DUPLICATE, primary, checksum);
                }
                // Source changed: never overwrite; save a separate revision.
                Path revision = primary.resolve(REVISIONS_DIR).resolve(checksum.substring(0, 12));
                writeSnapshot(revision, month, sourceHtml, checksum);
                return new SaveResult(SaveStatus.REVISION_SAVED, revision, checksum);
            }
            writeSnapshot(primary, month, sourceHtml, checksum);
            return new SaveResult(SaveStatus.CREATED, primary, checksum);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save snapshot for " + month.applicationCityId()
                    + " " + month.yearMonth(), e);
        }
    }

    private void writeSnapshot(Path dir, HabousReferenceMonth month, String html, String checksum)
            throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(SOURCE_HTML), html, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(SOURCE_SHA), checksum + "\n", StandardCharsets.UTF_8);
        writeCsv(dir.resolve(PRAYER_CSV), month.dailyRecords());
        writeMetadata(dir.resolve(METADATA_JSON), month);
    }

    private void writeCsv(Path path, List<HabousReferenceDay> days) throws IOException {
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("date,fajr,sunrise,dhuhr,asr,maghrib,isha\n");
            for (HabousReferenceDay d : days) {
                w.write(d.date() + "," + d.fajr().format(HM) + "," + d.sunrise().format(HM) + ","
                        + d.dhuhr().format(HM) + "," + d.asr().format(HM) + ","
                        + d.maghrib().format(HM) + "," + d.isha().format(HM) + "\n");
            }
        }
    }

    private void writeMetadata(Path path, HabousReferenceMonth month) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("applicationCityId", month.applicationCityId());
        m.put("habousCityId", month.habousCityId());
        m.put("habousArabicName", month.habousArabicName());
        m.put("year", month.year());
        m.put("month", month.month());
        m.put("fetchedAt", month.fetchedAt().toString());
        m.put("sourceUrl", month.sourceUrl());
        m.put("sourceChecksum", month.sourceChecksum());
        m.put("parserVersion", month.parserVersion());
        m.put("dayCount", month.dailyRecords().size());
        m.put("complete", month.isComplete());
        m.put("missingDays", month.missingDays().stream().map(LocalDate::toString).toList());
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(m, w);
            w.write("\n");
        }
    }

    private StoredSnapshot read(Path dir) {
        try {
            MetadataDto meta = GSON.fromJson(
                    Files.readString(dir.resolve(METADATA_JSON), StandardCharsets.UTF_8), MetadataDto.class);
            List<HabousReferenceDay> days = readCsv(dir.resolve(PRAYER_CSV));
            HabousReferenceMonth month = new HabousReferenceMonth(
                    meta.applicationCityId, meta.habousCityId, meta.habousArabicName,
                    meta.year, meta.month, Instant.parse(meta.fetchedAt), meta.sourceUrl,
                    meta.sourceChecksum, meta.parserVersion, days);
            return new StoredSnapshot(dir, month, meta.sourceChecksum);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read snapshot at " + dir, e);
        }
    }

    private List<HabousReferenceDay> readCsv(Path path) throws IOException {
        List<HabousReferenceDay> days = new ArrayList<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split(",");
            days.add(new HabousReferenceDay(
                    LocalDate.parse(f[0]), LocalTime.parse(f[1], HM), LocalTime.parse(f[2], HM),
                    LocalTime.parse(f[3], HM), LocalTime.parse(f[4], HM), LocalTime.parse(f[5], HM),
                    LocalTime.parse(f[6], HM)));
        }
        return days;
    }

    private static final class MetadataDto {
        String applicationCityId;
        String habousCityId;
        String habousArabicName;
        int year;
        int month;
        String fetchedAt;
        String sourceUrl;
        String sourceChecksum;
        String parserVersion;
    }
}
