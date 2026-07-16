package io.github.cyonecoder.salatapi.verification;

import io.github.cyonecoder.salatapi.archive.io.AtomicJsonFileWriter;
import io.github.cyonecoder.salatapi.config.SalatApiProperties;
import io.github.cyonecoder.salatapi.verification.model.DailyVerificationReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Stores one daily verification report per date under {@code verification/daily/}
 * (spec 20).
 */
@Repository
public class VerificationReportRepository {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SalatApiProperties properties;
    private final AtomicJsonFileWriter jsonFiles;

    public VerificationReportRepository(SalatApiProperties properties, AtomicJsonFileWriter jsonFiles) {
        this.properties = properties;
        this.jsonFiles = jsonFiles;
    }

    /** @param report the report to store */
    public Path save(DailyVerificationReport report) {
        Path file = reportFile(report.date());
        jsonFiles.write(file, report);
        return file;
    }

    /** @param date the report date */
    public Optional<DailyVerificationReport> find(LocalDate date) {
        Path file = reportFile(date);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(jsonFiles.read(file, DailyVerificationReport.class));
    }

    private Path reportFile(LocalDate date) {
        return properties.verificationDailyDirectory().resolve("verify-" + DATE.format(date) + ".json");
    }
}
