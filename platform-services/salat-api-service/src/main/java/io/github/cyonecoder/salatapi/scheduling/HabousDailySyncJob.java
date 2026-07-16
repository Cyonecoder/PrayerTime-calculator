package io.github.cyonecoder.salatapi.scheduling;

import io.github.cyonecoder.salatapi.archive.model.ArchiveFailureCode;
import io.github.cyonecoder.salatapi.archive.repository.SyncReportRepository;
import io.github.cyonecoder.salatapi.archive.service.GregorianArchiveAssembler.ObservedPage;
import io.github.cyonecoder.salatapi.archive.service.MonthlyArchiveService;
import io.github.cyonecoder.salatapi.habous.client.HabousClient;
import io.github.cyonecoder.salatapi.habous.model.CityCatalogue;
import io.github.cyonecoder.salatapi.habous.model.CityCatalogueEntry;
import io.github.cyonecoder.salatapi.habous.model.HabousCitySyncResult;
import io.github.cyonecoder.salatapi.habous.service.HabousCityCatalogService;
import io.github.cyonecoder.salatapi.habous.service.HabousCollectionService;
import io.github.cyonecoder.salatapi.verification.DailyVerificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.YearMonth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily scheduled workflow (spec 20): refresh the catalogue, collect every
 * enabled official city, assemble and publish the Gregorian monthly archives,
 * verify sec-approach against official data, and record a run report — all under a
 * single process-and-filesystem lock.
 *
 * <p><strong>One city's failure never aborts the run</strong> or erases another
 * city's data: collection isolates failures per city, and archives are assembled
 * against the whole catalogue so a failed city is listed as unavailable rather
 * than dropped.
 */
@Component
public class HabousDailySyncJob {

    private static final Logger log = LoggerFactory.getLogger(HabousDailySyncJob.class);

    private final SyncExecutionLock lock;
    private final HabousCityCatalogService catalog;
    private final HabousCollectionService collection;
    private final MonthlyArchiveService archives;
    private final io.github.cyonecoder.salatapi.archive.repository.RawHabousSnapshotRepository rawSnapshots;
    private final DailyVerificationService verification;
    private final SyncReportRepository syncReports;
    private final HabousClient client;
    private final Clock clock;

    public HabousDailySyncJob(
            SyncExecutionLock lock,
            HabousCityCatalogService catalog,
            HabousCollectionService collection,
            MonthlyArchiveService archives,
            io.github.cyonecoder.salatapi.archive.repository.RawHabousSnapshotRepository rawSnapshots,
            DailyVerificationService verification,
            SyncReportRepository syncReports,
            HabousClient client,
            Clock clock) {
        this.lock = lock;
        this.catalog = catalog;
        this.collection = collection;
        this.archives = archives;
        this.rawSnapshots = rawSnapshots;
        this.verification = verification;
        this.syncReports = syncReports;
        this.client = client;
        this.clock = clock;
    }

    /**
     * The scheduled entry point. Defaults to 03:15 Morocco time daily; the cron is
     * overridable via {@code salat.habous.sync-cron}.
     */
    @Scheduled(cron = "${salat.habous.sync-cron:0 15 3 * * *}", zone = "Africa/Casablanca")
    public void scheduledSync() {
        runOnce();
    }

    /**
     * Runs the workflow once, acquiring the lock. Also the entry point for a manual
     * trigger.
     *
     * @return the report of the run (a SKIPPED report if the lock was held)
     */
    public SyncJobReport runOnce() {
        LocalDate runDate = LocalDate.now(clock);
        Instant startedAt = clock.instant();

        var handle = lock.tryAcquire();
        if (handle.isEmpty()) {
            SyncJobReport skipped = new SyncJobReport(
                    runDate, startedAt, clock.instant(), SyncJobReport.Outcome.SKIPPED,
                    0, 0, 0, false, List.of(), false, List.of(),
                    List.of("a run was already in progress"));
            syncReports.save(skipped);
            return skipped;
        }

        try (SyncExecutionLock.Handle held = handle.get()) {
            return execute(runDate, startedAt);
        }
    }

    private SyncJobReport execute(LocalDate runDate, Instant startedAt) {
        List<String> warnings = new ArrayList<>();
        boolean catalogueChanged = false;

        // 1. Refresh the official city catalogue.
        try {
            String directoryPage = client.fetchCurrentPublishedTimetable(firstKnownVille());
            var refresh = catalog.refresh(directoryPage);
            catalogueChanged = refresh.diff().hasChanges();
            if (catalogueChanged) {
                warnings.add("official catalogue changed during collection");
            }
        } catch (RuntimeException e) {
            // A catalogue refresh failure is not fatal: fall back to the last known
            // catalogue and still collect. Record it as a warning.
            log.warn("Catalogue refresh failed; using the last known catalogue: {}", e.getMessage());
            warnings.add("catalogue refresh failed: " + e.getMessage());
        }

        CityCatalogue catalogue = catalog.current();
        List<CityCatalogueEntry> enabled = catalogue.presentEntries();

        // 2-3. Collect every enabled official city (failures isolated per city).
        HabousCollectionService.CollectionOutcome outcome = collection.collectAll(enabled);
        Map<String, ArchiveFailureCode> failures = failureCodes(outcome.results());

        // 4. Assemble and publish the Gregorian monthly archives atomically.
        //    Assemble the months this run touched from ALL stored snapshots, not just
        //    this run's pages, so a Gregorian month completed by two Hijri pages
        //    (collected weeks apart) accumulates instead of being rebuilt and losing
        //    the other page's days. Fresh pages are already saved as snapshots above.
        Instant syncedAt = clock.instant();
        List<ObservedPage> allStored = rawSnapshots.listAllPrimary().stream()
                .map(s -> new ObservedPage(s.metadata(), s.days()))
                .toList();
        archives.publishAccumulated(
                outcome.pages(), allStored, enabled, failures, syncedAt, catalogueChanged, warnings);

        // 5-6. Verify sec-approach against official data for the covered months.
        boolean verificationRan = runVerification(outcome.pages());

        // 7. Store the synchronization report.
        SyncJobReport report = report(
                runDate, startedAt, catalogue, outcome.results(), catalogueChanged,
                outcome.pages(), verificationRan, warnings);
        syncReports.save(report);
        log.info("Sync {} finished: {} — {}/{} cities collected, {} months published",
                runDate, report.outcome(), report.collectedCityCount(), report.officialCityCount(),
                report.publishedMonths().size());
        return report;
    }

    private boolean runVerification(List<ObservedPage> pages) {
        var dates = pages.stream()
                .flatMap(p -> p.days().stream())
                .map(d -> d.date())
                .distinct()
                .sorted()
                .toList();
        if (dates.isEmpty()) {
            return false;
        }
        // Verify each covered date against its published archive.
        dates.forEach(verification::verify);
        return true;
    }

    private Map<String, ArchiveFailureCode> failureCodes(List<HabousCitySyncResult> results) {
        Map<String, ArchiveFailureCode> failures = new LinkedHashMap<>();
        for (HabousCitySyncResult result : results) {
            result.archiveFailureCode().ifPresent(code -> failures.put(result.habousCityId(), code));
        }
        return failures;
    }

    private SyncJobReport report(
            LocalDate runDate, Instant startedAt, CityCatalogue catalogue,
            List<HabousCitySyncResult> results, boolean catalogueChanged,
            List<ObservedPage> pages, boolean verificationRan, List<String> warnings) {

        int collected = (int) results.stream().filter(HabousCitySyncResult::isStored).count();
        List<SyncJobReport.FailedCity> failed = new ArrayList<>();
        for (HabousCitySyncResult result : results) {
            if (!result.isStored()) {
                failed.add(new SyncJobReport.FailedCity(result.habousCityId(),
                        result.status() + (result.detail() == null ? "" : ": " + result.detail())));
            }
        }
        List<String> months = pages.stream()
                .flatMap(p -> p.days().stream())
                .map(d -> YearMonth.from(d.date()).toString())
                .distinct()
                .sorted()
                .toList();

        SyncJobReport.Outcome outcome = failed.isEmpty()
                ? SyncJobReport.Outcome.SUCCESS
                : SyncJobReport.Outcome.PARTIAL_SUCCESS;

        return new SyncJobReport(
                runDate, startedAt, clock.instant(), outcome,
                catalogue.observedCount(), collected, failed.size(), catalogueChanged,
                months, verificationRan, failed, warnings);
    }

    /**
     * A ville id known to exist, used to fetch a page carrying the ville dropdown
     * for the catalogue refresh. Ville 1 (Rabat) is the canonical first entry.
     */
    private String firstKnownVille() {
        return catalog.current().presentEntries().stream()
                .map(CityCatalogueEntry::habousCityId)
                .findFirst()
                .orElse("1");
    }
}
