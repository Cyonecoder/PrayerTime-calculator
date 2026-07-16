package io.github.cyonecoder.salatapi.verification;

import io.github.cyonecoder.salatapi.archive.model.ArchivedDay;
import io.github.cyonecoder.salatapi.archive.model.GregorianMonthlyArchive;
import io.github.cyonecoder.salatapi.archive.model.MonthlyCitySchedule;
import io.github.cyonecoder.salatapi.archive.service.MonthlyArchiveService;
import io.github.cyonecoder.salatapi.common.model.PrayerTimes;
import io.github.cyonecoder.salatapi.secapproach.exception.CityNotSupportedException;
import io.github.cyonecoder.salatapi.secapproach.service.SecApproachCalculator;
import io.github.cyonecoder.salatapi.verification.model.CityDailyVerification;
import io.github.cyonecoder.salatapi.verification.model.DailyVerificationReport;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Compares official data against sec-approach for the verification intersection
 * on a given date (spec 20).
 *
 * <p>The intersection is the sec-approach cities (the trained 48) that have
 * official data for the date, drawn from the published monthly archive. Cities
 * outside the 48 are never compared — collecting official data for a city does not
 * make it a sec-approach city.
 */
@Service
public class DailyVerificationService {

    private static final Logger log = LoggerFactory.getLogger(DailyVerificationService.class);

    private final MonthlyArchiveService archives;
    private final SecApproachCalculator secApproach;
    private final PrayerTimeComparisonService comparison;
    private final VerificationReportRepository reports;
    private final Clock clock;

    public DailyVerificationService(
            MonthlyArchiveService archives,
            SecApproachCalculator secApproach,
            PrayerTimeComparisonService comparison,
            VerificationReportRepository reports,
            Clock clock) {
        this.archives = archives;
        this.secApproach = secApproach;
        this.comparison = comparison;
        this.reports = reports;
        this.clock = clock;
    }

    /**
     * Verifies a date against the published archive for its month and stores the
     * report.
     *
     * @param date the date to verify
     * @return the stored report
     */
    public DailyVerificationReport verify(LocalDate date) {
        Optional<GregorianMonthlyArchive> archive = archives.find(YearMonth.from(date));
        List<CityDailyVerification> comparisons = new ArrayList<>();
        java.util.Set<String> officialChecksums = new java.util.TreeSet<>();

        if (archive.isPresent()) {
            for (MonthlyCitySchedule city : archive.get().secApproachCities()) {
                officialDay(city, date).ifPresent(official ->
                        compareCity(city, date, official).ifPresent(comparison -> {
                            comparisons.add(comparison);
                            officialChecksums.add(official.sourceChecksum());
                        }));
            }
        }

        DailyVerificationReport report = DailyVerificationReport.of(
                date, secApproach.parameterVersion(), clock.instant(),
                comparisons, List.copyOf(officialChecksums));
        reports.save(report);
        log.info("Verified {}: {}/{} sec-approach cities agreed exactly to the minute; "
                        + "overall exact ratio {} (window: {})",
                date, report.exactCityCount(), report.comparedCityCount(),
                String.format(java.util.Locale.ROOT, "%.3f", report.overall().exactRatio()),
                DailyVerificationReport.ACCURACY_DISCLAIMER);
        return report;
    }

    private Optional<CityDailyVerification> compareCity(
            MonthlyCitySchedule city, LocalDate date, ArchivedDay official) {
        try {
            PrayerTimes secApproachTimes = secApproach.computeTimes(city.applicationCityId(), date);
            PrayerTimes officialTimes = new PrayerTimes(
                    date, official.fajr(), official.sunrise(), official.dhuhr(),
                    official.asr(), official.maghrib(), official.isha());
            return Optional.of(CityDailyVerification.of(
                    city.habousCityId(), city.applicationCityId(), date,
                    comparison.compare(officialTimes, secApproachTimes)));
        } catch (CityNotSupportedException e) {
            // Should not happen for a secApproachSupported city, but never guess.
            log.warn("Skipping verification for {}: {}", city.applicationCityId(), e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ArchivedDay> officialDay(MonthlyCitySchedule city, LocalDate date) {
        return city.days().stream().filter(d -> d.date().equals(date)).findFirst();
    }
}
