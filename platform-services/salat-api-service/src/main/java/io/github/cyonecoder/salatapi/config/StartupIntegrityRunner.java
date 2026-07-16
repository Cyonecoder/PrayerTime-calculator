package io.github.cyonecoder.salatapi.config;

import io.github.cyonecoder.salatapi.archive.model.MonthlyArchiveManifest;
import io.github.cyonecoder.salatapi.archive.repository.MonthlyArchiveRepository;
import io.github.cyonecoder.salatapi.common.checksum.Sha256Service;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The filesystem half of the startup sequence (spec 27): create the runtime data
 * directories, scan existing snapshots and archives, and validate each archive's
 * manifest checksum — all <strong>offline</strong>.
 *
 * <p>The resource half (validating the 191 / 67 / 60 / 48 groups and that every
 * sec-approach city is in the registry) already happens fail-fast when those beans
 * are constructed, so a bad resource stops the context from starting. This runner
 * handles the parts that need the data directory to exist.
 *
 * <p><strong>No network.</strong> Startup never contacts Habous, so the
 * sec-approach and read endpoints come up even when the official site is down. A
 * checksum mismatch is logged, never fatal — a single corrupt archive must not
 * stop the service (rebuild-month recovers it).
 */
@Component
public class StartupIntegrityRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupIntegrityRunner.class);

    private final SalatApiProperties properties;
    private final MonthlyArchiveRepository archives;
    private final Sha256Service sha256;

    public StartupIntegrityRunner(
            SalatApiProperties properties, MonthlyArchiveRepository archives, Sha256Service sha256) {
        this.properties = properties;
        this.archives = archives;
        this.sha256 = sha256;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        createDataDirectories();
        int archiveCount = validateArchives();
        Path resolved = properties.dataDirectory().toAbsolutePath().normalize();
        if (archiveCount == 0) {
            log.warn("Data directory: {} (no monthly archives yet). Official-data requests will "
                    + "return OFFICIAL_DATA_NOT_AVAILABLE until a sync runs into THIS directory. "
                    + "Run: --salat.job=sync-now --salat.data-directory={}", resolved, resolved);
        } else {
            log.info("Data directory: {} ({} monthly archive(s) validated)", resolved, archiveCount);
        }
    }

    /** Creates every runtime data subdirectory so later writes never race on mkdir. */
    void createDataDirectories() {
        List<Path> directories = List.of(
                properties.dataDirectory(),
                properties.catalogDirectory(),
                properties.catalogHistoryDirectory(),
                properties.rawDirectory(),
                properties.archivesDirectory(),
                properties.verificationDailyDirectory(),
                properties.jobsDirectory(),
                properties.stagingDirectory(),
                properties.rejectedDirectory());
        try {
            for (Path directory : directories) {
                Files.createDirectories(directory);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create the runtime data directories", e);
        }
    }

    /**
     * Validates that each stored archive matches its manifest checksum.
     *
     * @return the number of archives found; mismatches are warned, not fatal
     */
    int validateArchives() {
        List<YearMonth> months = archives.listMonths();
        for (YearMonth month : months) {
            MonthlyArchiveManifest manifest = archives.findManifest(month).orElse(null);
            String json = archives.findArchiveJson(month).orElse(null);
            if (manifest == null || json == null) {
                log.warn("Archive {} is missing its manifest or archive file", month);
                continue;
            }
            String actual = sha256.hex(json);
            if (!actual.equals(manifest.archiveSha256())) {
                log.warn("Archive {} checksum mismatch (manifest {}, file {}); rebuild-month {} to repair",
                        month, manifest.archiveSha256(), actual, month);
            }
        }
        return months.size();
    }
}
