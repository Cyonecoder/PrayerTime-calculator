package io.github.cyonecoder.salatapi.verification;

import io.github.cyonecoder.salatapi.archive.io.AtomicJsonFileWriter;
import io.github.cyonecoder.salatapi.config.SalatApiProperties;
import io.github.cyonecoder.salatapi.verification.model.DailyVerificationReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
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

    /** @return the most recent verification report, if any */
    public Optional<DailyVerificationReport> findLatest() {
        return all().stream().max(java.util.Comparator.comparing(DailyVerificationReport::date));
    }

    /**
     * @param year  the Gregorian year
     * @param month the Gregorian month
     * @return every verification report whose date falls in that month, ascending
     */
    public List<DailyVerificationReport> findInMonth(int year, int month) {
        java.time.YearMonth target = java.time.YearMonth.of(year, month);
        return all().stream()
                .filter(r -> java.time.YearMonth.from(r.date()).equals(target))
                .sorted(java.util.Comparator.comparing(DailyVerificationReport::date))
                .toList();
    }

    private List<DailyVerificationReport> all() {
        Path dir = properties.verificationDailyDirectory();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var files = Files.list(dir)) {
            return files
                    .filter(p -> p.getFileName().toString().startsWith("verify-")
                            && p.getFileName().toString().endsWith(".json"))
                    .map(p -> jsonFiles.read(p, DailyVerificationReport.class))
                    .toList();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Failed to list verification reports in " + dir, e);
        }
    }

    private Path reportFile(LocalDate date) {
        return properties.verificationDailyDirectory().resolve("verify-" + DATE.format(date) + ".json");
    }
}
