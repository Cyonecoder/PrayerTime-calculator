package io.github.cyonecoder.salatapi.config;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Service-wide configuration and the runtime data layout (spec 15).
 *
 * <p>Everything the service writes lives under one local directory, set by
 * {@code SALAT_DATA_DIRECTORY} and defaulting to {@code ./data}. There is no
 * database.
 *
 * <pre>
 * data/
 * ├── catalog/       current-habous-villes.json + history/
 * ├── raw/habous/    YYYY/MM/DD/{habousCityId}/ — immutable official sources
 * ├── archives/      gregorian/YYYY/MM/ — assembled monthly archives
 * ├── verification/  daily/ — sec-approach vs official reports
 * ├── jobs/          sync job reports
 * ├── staging/       in-progress work, never served
 * └── rejected/      pages that arrived but could not be trusted
 * </pre>
 *
 * @param dataDirectory the single local JSON data directory
 */
@ConfigurationProperties(prefix = "salat")
public record SalatApiProperties(@DefaultValue("./data") Path dataDirectory) {

    // --- catalog -------------------------------------------------------------

    /** @return where the official city catalogue lives */
    public Path catalogDirectory() {
        return dataDirectory.resolve("catalog");
    }

    /** @return the current observed official ville catalogue */
    public Path currentCatalogFile() {
        return catalogDirectory().resolve("current-habous-villes.json");
    }

    /**
     * @return superseded catalogues, kept forever. The catalogue is the only
     *         record of what the official source published on a given day, and it
     *         cannot be re-fetched.
     */
    public Path catalogHistoryDirectory() {
        return catalogDirectory().resolve("history");
    }

    // --- raw sources ---------------------------------------------------------

    /** @return the root of the immutable raw official page snapshots */
    public Path rawDirectory() {
        return dataDirectory.resolve("raw").resolve("habous");
    }

    /**
     * The directory holding one city's source as fetched on one day.
     *
     * <p>Keyed by fetch <em>date</em>, not by the period the page covers: the
     * source publishes only "now", so the fetch date is the only thing that
     * identifies which observation this was.
     *
     * @param fetchedOn    the day the page was fetched
     * @param habousCityId the official ville id — the stable archive identity,
     *                     used even for cities with no application slug
     */
    public Path rawSnapshotDirectory(LocalDate fetchedOn, String habousCityId) {
        return rawDirectory()
                .resolve(String.format("%04d", fetchedOn.getYear()))
                .resolve(String.format("%02d", fetchedOn.getMonthValue()))
                .resolve(String.format("%02d", fetchedOn.getDayOfMonth()))
                .resolve(habousCityId);
    }

    // --- archives ------------------------------------------------------------

    /** @return the root of the assembled Gregorian monthly archives */
    public Path archivesDirectory() {
        return dataDirectory.resolve("archives").resolve("gregorian");
    }

    /** @param month the Gregorian month */
    public Path archiveDirectory(YearMonth month) {
        return archivesDirectory()
                .resolve(String.format("%04d", month.getYear()))
                .resolve(String.format("%02d", month.getMonthValue()));
    }

    /** @param month the Gregorian month */
    public Path archiveFile(YearMonth month) {
        return archiveDirectory(month).resolve(
                String.format("habous-prayer-times-%04d-%02d.json",
                        month.getYear(), month.getMonthValue()));
    }

    /** @param month the Gregorian month */
    public Path archiveManifestFile(YearMonth month) {
        return archiveDirectory(month).resolve("manifest.json");
    }

    /** @param month the Gregorian month */
    public Path archiveRevisionsDirectory(YearMonth month) {
        return archiveDirectory(month).resolve("revisions");
    }

    // --- reports and working areas -------------------------------------------

    /** @return where daily verification reports live */
    public Path verificationDailyDirectory() {
        return dataDirectory.resolve("verification").resolve("daily");
    }

    /** @return where sync job reports live */
    public Path jobsDirectory() {
        return dataDirectory.resolve("jobs");
    }

    /** @return in-progress work; never served, safe to delete */
    public Path stagingDirectory() {
        return dataDirectory.resolve("staging");
    }

    /**
     * @return pages that arrived but could not be trusted (unparseable, wrong
     *         city, wrong shape). Kept rather than discarded: a rejected page is
     *         the evidence of what the source actually served, and it cannot be
     *         re-fetched later.
     */
    public Path rejectedDirectory() {
        return dataDirectory.resolve("rejected");
    }
}
