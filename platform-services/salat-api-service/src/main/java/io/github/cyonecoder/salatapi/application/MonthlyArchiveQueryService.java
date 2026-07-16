package io.github.cyonecoder.salatapi.application;

import io.github.cyonecoder.salatapi.api.dto.response.MonthSummaryResponse;
import io.github.cyonecoder.salatapi.api.exception.ResourceNotFoundException;
import io.github.cyonecoder.salatapi.archive.model.ArchivedDay;
import io.github.cyonecoder.salatapi.archive.model.CityArchiveStatus;
import io.github.cyonecoder.salatapi.archive.model.GregorianMonthlyArchive;
import io.github.cyonecoder.salatapi.archive.model.MonthlyArchiveManifest;
import io.github.cyonecoder.salatapi.archive.model.MonthlyCitySchedule;
import io.github.cyonecoder.salatapi.archive.repository.MonthlyArchiveRepository;
import io.github.cyonecoder.salatapi.city.ApplicationHabousMapping;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Read access to the published monthly archives for the API (spec 23.3–23.7).
 *
 * <p>Every method returns official data as stored; none calculates a fallback.
 */
@Service
public class MonthlyArchiveQueryService {

    private final MonthlyArchiveRepository archives;
    private final ApplicationHabousMapping mapping;

    public MonthlyArchiveQueryService(
            MonthlyArchiveRepository archives, ApplicationHabousMapping mapping) {
        this.archives = archives;
        this.mapping = mapping;
    }

    /** @return a summary per archived month, ascending (spec 23.3) */
    public List<MonthSummaryResponse> listMonthSummaries() {
        return archives.listMonths().stream()
                .map(archives::findManifest)
                .flatMap(Optional::stream)
                .map(this::toSummary)
                .toList();
    }

    /**
     * @param year             the Gregorian year
     * @param month            the Gregorian month
     * @param includeUnavailable whether to include cities with no usable days
     * @return the full archive, optionally without the unavailable cities (spec 23.4)
     */
    public GregorianMonthlyArchive month(int year, int month, boolean includeUnavailable) {
        GregorianMonthlyArchive archive = requireArchive(year, month);
        if (includeUnavailable) {
            return archive;
        }
        List<MonthlyCitySchedule> present = archive.cities().stream()
                .filter(c -> c.status() != CityArchiveStatus.UNAVAILABLE)
                .toList();
        return new GregorianMonthlyArchive(
                archive.schemaVersion(), archive.source(), archive.period(), archive.periodType(),
                archive.timezone(), archive.status(), archive.generatedAt(), archive.lastUpdatedAt(),
                archive.officialCatalogCityCount(), present.size(), archive.completeCityCount(),
                archive.partialCityCount(), archive.failedCityCount(), present);
    }

    /** @return the archive file as raw JSON text for download (spec 23.5) */
    public String monthFileJson(int year, int month) {
        return archives.findArchiveJson(YearMonth.of(year, month))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No archive for " + period(year, month)));
    }

    /** @return one official city's month by ville id (spec 23.6) */
    public MonthlyCitySchedule cityMonth(int year, int month, String habousCityId) {
        return requireArchive(year, month).city(habousCityId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No city " + habousCityId + " in the " + period(year, month) + " archive"));
    }

    /** @return one official city's month by application slug (spec 23.6) */
    public MonthlyCitySchedule cityMonthByApplicationId(int year, int month, String applicationCityId) {
        String villeId = mapping.findByApplicationCityId(applicationCityId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No verified Habous mapping for application city '" + applicationCityId + "'"))
                .habousCityId();
        return cityMonth(year, month, villeId);
    }

    /** @return one official day for a city by ville id, or empty if not collected (spec 23.7) */
    public Optional<ArchivedDay> officialDay(String habousCityId, LocalDate date) {
        return archives.findArchive(YearMonth.from(date))
                .flatMap(a -> a.city(habousCityId))
                .flatMap(city -> city.days().stream().filter(d -> d.date().equals(date)).findFirst());
    }

    /** @return the ville id mapped to an application slug, for the official-day lookup */
    public String requireVilleId(String applicationCityId) {
        return mapping.findByApplicationCityId(applicationCityId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No verified Habous mapping for application city '" + applicationCityId + "'"))
                .habousCityId();
    }

    private GregorianMonthlyArchive requireArchive(int year, int month) {
        return archives.findArchive(YearMonth.of(year, month))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No archive for " + period(year, month)));
    }

    private MonthSummaryResponse toSummary(MonthlyArchiveManifest m) {
        return new MonthSummaryResponse(
                m.period(), m.status(), m.officialCatalogCityCount(), m.archiveCityCount(),
                m.completeCityCount(), m.partialCityCount(), m.unavailableCityCount(),
                m.expectedDaysPerCompleteCity(), m.lastSuccessfulSyncAt());
    }

    private static String period(int year, int month) {
        return String.format("%04d-%02d", year, month);
    }
}
