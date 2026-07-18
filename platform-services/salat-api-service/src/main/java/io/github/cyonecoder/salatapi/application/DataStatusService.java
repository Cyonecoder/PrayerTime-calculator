package io.github.cyonecoder.salatapi.application;

import io.github.cyonecoder.salatapi.api.dto.response.DataStatusResponse;
import io.github.cyonecoder.salatapi.api.dto.response.ServiceInfoResponse;
import io.github.cyonecoder.salatapi.archive.model.MonthlyArchiveManifest;
import io.github.cyonecoder.salatapi.archive.repository.MonthlyArchiveRepository;
import io.github.cyonecoder.salatapi.archive.repository.SyncReportRepository;
import io.github.cyonecoder.salatapi.city.ApplicationHabousMapping;
import io.github.cyonecoder.salatapi.city.MoroccoCityRegistry;
import io.github.cyonecoder.salatapi.config.SalatApiProperties;
import io.github.cyonecoder.salatapi.habous.service.HabousCityCatalogService;
import io.github.cyonecoder.salatapi.scheduling.SyncExecutionLock;
import io.github.cyonecoder.salatapi.scheduling.SyncJobReport;
import io.github.cyonecoder.salatapi.secapproach.configuration.SecApproachParameters;
import io.github.cyonecoder.salatapi.verification.VerificationReportRepository;
import io.github.cyonecoder.salatapi.verification.model.DailyVerificationReport;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Assembles the service-info and data-status views (spec 23.1 and 23.15).
 */
@Service
public class DataStatusService {

    private final HabousCityCatalogService catalog;
    private final MoroccoCityRegistry registry;
    private final ApplicationHabousMapping mapping;
    private final SecApproachParameters parameters;
    private final MonthlyArchiveRepository archives;
    private final SyncReportRepository syncReports;
    private final VerificationReportRepository verificationReports;
    private final SyncExecutionLock lock;
    private final SalatApiProperties properties;
    private final Clock clock;
    private final boolean schedulingEnabled;

    public DataStatusService(
            HabousCityCatalogService catalog,
            MoroccoCityRegistry registry,
            ApplicationHabousMapping mapping,
            SecApproachParameters parameters,
            MonthlyArchiveRepository archives,
            SyncReportRepository syncReports,
            VerificationReportRepository verificationReports,
            SyncExecutionLock lock,
            SalatApiProperties properties,
            Clock clock,
            @Value("${salat.habous.enabled:true}") boolean habousEnabled,
            @Value("${salat.scheduling.enabled:true}") boolean schedulingEnabled) {
        this.catalog = catalog;
        this.registry = registry;
        this.mapping = mapping;
        this.parameters = parameters;
        this.archives = archives;
        this.syncReports = syncReports;
        this.verificationReports = verificationReports;
        this.lock = lock;
        this.properties = properties;
        this.clock = clock;
        // The scheduler runs only if both the canonical Habous flag and the alias are on.
        this.schedulingEnabled = habousEnabled && schedulingEnabled;
    }

    /** @return service information (spec 23.1) */
    public ServiceInfoResponse info() {
        Optional<SyncJobReport> lastSuccess = syncReports.findLatestSuccessful();
        Optional<YearMonth> latestMonth = archives.listMonths().stream().max(YearMonth::compareTo);
        Optional<MonthlyArchiveManifest> latestManifest = latestMonth.flatMap(archives::findManifest);

        return new ServiceInfoResponse(
                applicationVersion(),
                catalog.observedCount(),
                registry.count(),
                mapping.count(),
                parameters.count(),
                parameters.parameterVersion(),
                lastSuccess.map(SyncJobReport::runDate).orElse(null),
                latestOfficialDate().orElse(null),
                latestManifest.map(m -> m.status().name()).orElse(null));
    }

    /** @return the operational data status (spec 23.15) */
    public DataStatusResponse dataStatus() {
        Map<String, String> archiveStatuses = new LinkedHashMap<>();
        for (YearMonth month : archives.listMonths()) {
            archives.findManifest(month)
                    .ifPresent(m -> archiveStatuses.put(m.period(), m.status().name()));
        }

        Long freshnessDays = latestOfficialDate()
                .map(date -> ChronoUnit.DAYS.between(date, LocalDate.now(clock)))
                .orElse(null);

        DataStatusResponse.LatestVerification latestVerification = verificationReports.findLatest()
                .map(this::toLatestVerification)
                .orElse(null);

        return new DataStatusResponse(
                properties.dataDirectory().toAbsolutePath().normalize().toString(),
                schedulingEnabled,
                lock.isRunning(),
                catalog.observedCount(),
                catalog.current().refreshedAt(),
                syncReports.findLatestSuccessful().map(SyncJobReport::runDate).orElse(null),
                syncReports.findLatestFailed().map(SyncJobReport::runDate).orElse(null),
                archiveStatuses,
                freshnessDays,
                true,
                parameters.count(),
                latestVerification);
    }

    private DataStatusResponse.LatestVerification toLatestVerification(DailyVerificationReport report) {
        return new DataStatusResponse.LatestVerification(
                report.date(), report.comparedCityCount(), report.exactCityCount(),
                report.overall().exactRatio(), report.overall().withinOneRatio());
    }

    private Optional<LocalDate> latestOfficialDate() {
        return archives.listMonths().stream()
                .max(YearMonth::compareTo)
                .flatMap(archives::findArchive)
                .flatMap(archive -> archive.cities().stream()
                        .flatMap(c -> c.days().stream())
                        .map(d -> d.date())
                        .max(LocalDate::compareTo));
    }

    private String applicationVersion() {
        String version = getClass().getPackage().getImplementationVersion();
        return version == null ? "0.1.0" : version;
    }
}
