package io.github.cyonecoder.salatapi.archive.repository;

import io.github.cyonecoder.salatapi.archive.io.AtomicJsonFileWriter;
import io.github.cyonecoder.salatapi.config.SalatApiProperties;
import io.github.cyonecoder.salatapi.scheduling.SyncJobReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Stores one synchronization report per run under {@code jobs/} (spec 20).
 *
 * <p>Reports are keyed by run date. A later run on the same date overwrites the
 * earlier report (the latest run is the current truth), while the immutable raw
 * snapshots and archive revisions preserve the underlying data history.
 */
@Repository
public class SyncReportRepository {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SalatApiProperties properties;
    private final AtomicJsonFileWriter jsonFiles;

    public SyncReportRepository(SalatApiProperties properties, AtomicJsonFileWriter jsonFiles) {
        this.properties = properties;
        this.jsonFiles = jsonFiles;
    }

    /** @param report the report to store */
    public Path save(SyncJobReport report) {
        Path file = reportFile(report.runDate());
        jsonFiles.write(file, report);
        return file;
    }

    /** @param runDate the run date */
    public Optional<SyncJobReport> find(LocalDate runDate) {
        Path file = reportFile(runDate);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(jsonFiles.read(file, SyncJobReport.class));
    }

    private Path reportFile(LocalDate runDate) {
        return properties.jobsDirectory().resolve("sync-" + DATE.format(runDate) + ".json");
    }
}
