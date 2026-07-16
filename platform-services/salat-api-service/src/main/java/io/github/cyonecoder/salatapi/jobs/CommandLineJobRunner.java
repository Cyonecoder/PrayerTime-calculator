package io.github.cyonecoder.salatapi.jobs;

import io.github.cyonecoder.salatapi.archive.model.ArchiveFailureCode;
import io.github.cyonecoder.salatapi.archive.repository.RawHabousSnapshotRepository;
import io.github.cyonecoder.salatapi.archive.repository.RawHabousSnapshotRepository.StoredSnapshot;
import io.github.cyonecoder.salatapi.archive.service.GregorianArchiveAssembler.ObservedPage;
import io.github.cyonecoder.salatapi.archive.service.MonthlyArchiveService;
import io.github.cyonecoder.salatapi.habous.model.CityCatalogueEntry;
import io.github.cyonecoder.salatapi.habous.service.HabousCityCatalogService;
import io.github.cyonecoder.salatapi.scheduling.HabousDailySyncJob;
import io.github.cyonecoder.salatapi.verification.DailyVerificationService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs one-shot maintenance jobs from the command line and then lets the process
 * exit (spec 25).
 *
 * <p>Selected with {@code --salat.job=...}; intended to be run with
 * {@code --spring.main.web-application-type=none} so no HTTP server starts. When no
 * job is requested this runner does nothing, so the normal server boot is
 * unaffected.
 *
 * <p>These jobs are the <strong>only</strong> way to trigger synchronization or
 * rebuild: there are deliberately no public HTTP endpoints that sync, rebuild or
 * delete anything (spec 25). Every REST route is a read.
 */
@Component
public class CommandLineJobRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CommandLineJobRunner.class);

    private final HabousDailySyncJob syncJob;
    private final HabousCityCatalogService catalog;
    private final RawHabousSnapshotRepository rawSnapshots;
    private final MonthlyArchiveService archives;
    private final DailyVerificationService verification;
    private final Clock clock;

    public CommandLineJobRunner(
            HabousDailySyncJob syncJob,
            HabousCityCatalogService catalog,
            RawHabousSnapshotRepository rawSnapshots,
            MonthlyArchiveService archives,
            DailyVerificationService verification,
            Clock clock) {
        this.syncJob = syncJob;
        this.catalog = catalog;
        this.rawSnapshots = rawSnapshots;
        this.archives = archives;
        this.verification = verification;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        JobMode.parse(property(args, "salat.job")).ifPresent(mode -> {
            log.info("Running one-shot job: {}", mode.flag());
            switch (mode) {
                case SYNC_NOW -> syncNow();
                case REBUILD_ARCHIVES -> rebuildArchives();
                case REBUILD_MONTH -> rebuildMonth(requiredPeriod(args));
                case VERIFY_DATE -> verifyDate(requiredDate(args));
            }
            log.info("Job {} complete", mode.flag());
        });
    }

    private void syncNow() {
        var report = syncJob.runOnce();
        log.info("sync-now: {} — {}/{} cities collected, months {}",
                report.outcome(), report.collectedCityCount(), report.officialCityCount(),
                report.publishedMonths());
    }

    private void rebuildArchives() {
        List<ObservedPage> pages = storedPages();
        if (pages.isEmpty()) {
            log.warn("rebuild-archives: no stored raw snapshots found; nothing to rebuild");
            return;
        }
        List<CityCatalogueEntry> officialCities = catalog.current().presentEntries();
        var manifests = archives.publish(
                pages, officialCities, Map.of(), clock.instant(), false, List.of("rebuilt from stored snapshots"));
        log.info("rebuild-archives: republished {} month(s)", manifests.size());
    }

    private void rebuildMonth(YearMonth month) {
        // Only the pages that contribute a day to this month matter.
        List<ObservedPage> pages = storedPages().stream()
                .filter(p -> p.days().stream().anyMatch(d -> YearMonth.from(d.date()).equals(month)))
                .toList();
        List<CityCatalogueEntry> officialCities = catalog.current().presentEntries();
        var manifest = archives.publishMonth(
                month, pages, officialCities, Map.<String, ArchiveFailureCode>of(),
                clock.instant(), false, List.of("rebuilt from stored snapshots"));
        log.info("rebuild-month {}: status {}, {} cities", month, manifest.status(), manifest.archiveCityCount());
    }

    private void verifyDate(LocalDate date) {
        var report = verification.verify(date);
        log.info("verify-date {}: {}/{} cities exact ({}%)",
                date, report.exactCityCount(), report.comparedCityCount(),
                Math.round(report.overall().exactRatio() * 100));
    }

    private List<ObservedPage> storedPages() {
        return rawSnapshots.listAllPrimary().stream()
                .map(this::toPage)
                .toList();
    }

    private ObservedPage toPage(StoredSnapshot snapshot) {
        return new ObservedPage(snapshot.metadata(), snapshot.days());
    }

    private YearMonth requiredPeriod(ApplicationArguments args) {
        String period = property(args, "salat.job.period");
        if (period == null || period.isBlank()) {
            throw new IllegalArgumentException("rebuild-month requires --salat.job.period=YYYY-MM");
        }
        try {
            return YearMonth.parse(period.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid --salat.job.period '" + period + "'; expected YYYY-MM");
        }
    }

    private LocalDate requiredDate(ApplicationArguments args) {
        String date = property(args, "salat.job.date");
        if (date == null || date.isBlank()) {
            throw new IllegalArgumentException("verify-date requires --salat.job.date=YYYY-MM-DD");
        }
        try {
            return LocalDate.parse(date.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid --salat.job.date '" + date + "'; expected YYYY-MM-DD");
        }
    }

    /** Reads a property from the command line, falling back to the Spring environment. */
    private String property(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values != null && !values.isEmpty()) {
            return values.get(0);
        }
        return System.getProperty(name);
    }
}
