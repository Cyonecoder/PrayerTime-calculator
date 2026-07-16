package io.github.cyonecoder.salatapi.api.controller;

import io.github.cyonecoder.salatapi.api.dto.response.HabousCitySummaryResponse;
import io.github.cyonecoder.salatapi.api.dto.response.MonthSummaryResponse;
import io.github.cyonecoder.salatapi.api.dto.response.OfficialDayResponse;
import io.github.cyonecoder.salatapi.api.exception.ApiErrorCode;
import io.github.cyonecoder.salatapi.api.exception.ApiException;
import io.github.cyonecoder.salatapi.application.MonthlyArchiveQueryService;
import io.github.cyonecoder.salatapi.archive.model.ArchivedDay;
import io.github.cyonecoder.salatapi.archive.model.GregorianMonthlyArchive;
import io.github.cyonecoder.salatapi.archive.model.MonthlyCitySchedule;
import io.github.cyonecoder.salatapi.archive.repository.MonthlyArchiveRepository;
import io.github.cyonecoder.salatapi.city.ApplicationHabousMapping;
import io.github.cyonecoder.salatapi.city.CitySupportService;
import io.github.cyonecoder.salatapi.habous.model.CityCatalogueEntry;
import io.github.cyonecoder.salatapi.habous.service.HabousCityCatalogService;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The official Habous endpoints (spec 23.2–23.7).
 *
 * <p>These return official data only. None ever calculates a fallback — a missing
 * official day is a 404, not a sec-approach guess.
 */
@RestController
@RequestMapping("/api/v1/habous")
public class HabousController {

    private final HabousCityCatalogService catalog;
    private final ApplicationHabousMapping mapping;
    private final CitySupportService citySupport;
    private final MonthlyArchiveQueryService archives;
    private final MonthlyArchiveRepository archiveRepository;

    public HabousController(
            HabousCityCatalogService catalog,
            ApplicationHabousMapping mapping,
            CitySupportService citySupport,
            MonthlyArchiveQueryService archives,
            MonthlyArchiveRepository archiveRepository) {
        this.catalog = catalog;
        this.mapping = mapping;
        this.citySupport = citySupport;
        this.archives = archives;
        this.archiveRepository = archiveRepository;
    }

    // --- 23.2 cities ---------------------------------------------------------

    @GetMapping("/cities")
    public List<HabousCitySummaryResponse> cities(
            @RequestParam(required = false) Boolean mapped,
            @RequestParam(required = false) Boolean secApproachSupported,
            @RequestParam(required = false) Boolean available) {
        Set<String> availableVilles = availableVilleIds();
        return catalog.current().presentEntries().stream()
                .map(entry -> toSummary(entry, availableVilles))
                .filter(c -> mapped == null || c.mapped() == mapped)
                .filter(c -> secApproachSupported == null || c.secApproachSupported() == secApproachSupported)
                .filter(c -> available == null || c.available() == available)
                .toList();
    }

    @GetMapping("/cities/{habousCityId}")
    public HabousCitySummaryResponse city(@PathVariable String habousCityId) {
        CityCatalogueEntry entry = catalog.findById(habousCityId).orElseThrow(() ->
                new ApiException(ApiErrorCode.HABOUS_CITY_NOT_FOUND,
                        "No official Habous city with id " + habousCityId + "."));
        return toSummary(entry, availableVilleIds());
    }

    @GetMapping("/cities/by-application-id/{applicationCityId}")
    public HabousCitySummaryResponse cityByApplicationId(@PathVariable String applicationCityId) {
        String villeId = mapping.findByApplicationCityId(applicationCityId).orElseThrow(() ->
                new ApiException(ApiErrorCode.APPLICATION_CITY_NOT_MAPPED,
                        "No verified Habous mapping for application city '" + applicationCityId + "'."))
                .habousCityId();
        return city(villeId);
    }

    // --- 23.3 / 23.4 / 23.5 months -------------------------------------------

    @GetMapping("/months")
    public List<MonthSummaryResponse> months() {
        return archives.listMonthSummaries();
    }

    @GetMapping("/months/{year}/{month}")
    public GregorianMonthlyArchive month(
            @PathVariable int year,
            @PathVariable int month,
            @RequestParam(defaultValue = "true") boolean includeUnavailable) {
        return archives.month(year, month, includeUnavailable);
    }

    @GetMapping("/months/{year}/{month}/file")
    public ResponseEntity<String> monthFile(@PathVariable int year, @PathVariable int month) {
        String json = archives.monthFileJson(year, month);
        String filename = String.format("habous-prayer-times-%04d-%02d.json", year, month);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(json);
    }

    // --- 23.6 official city month --------------------------------------------

    @GetMapping("/months/{year}/{month}/cities/{habousCityId}")
    public MonthlyCitySchedule cityMonth(
            @PathVariable int year, @PathVariable int month, @PathVariable String habousCityId) {
        return archives.cityMonth(year, month, habousCityId);
    }

    @GetMapping("/months/{year}/{month}/application-cities/{applicationCityId}")
    public MonthlyCitySchedule cityMonthByApplicationId(
            @PathVariable int year, @PathVariable int month, @PathVariable String applicationCityId) {
        return archives.cityMonthByApplicationId(year, month, applicationCityId);
    }

    // --- 23.7 official day ---------------------------------------------------

    @GetMapping("/cities/{habousCityId}/prayer-times")
    public OfficialDayResponse officialDay(
            @PathVariable String habousCityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        ArchivedDay day = archives.officialDay(habousCityId, date).orElseThrow(() ->
                new ApiException(ApiErrorCode.OFFICIAL_DATA_NOT_AVAILABLE,
                        "No official data for Habous city " + habousCityId + " on " + date + "."));
        CityCatalogueEntry entry = catalog.findById(habousCityId).orElse(null);
        var support = citySupport.supportFor(habousCityId);
        return new OfficialDayResponse(
                habousCityId,
                entry == null ? null : entry.exactArabicName(),
                support.citySlug().orElse(null),
                OfficialDayResponse.SOURCE,
                day);
    }

    @GetMapping("/application-cities/{applicationCityId}/prayer-times")
    public OfficialDayResponse officialDayByApplicationId(
            @PathVariable String applicationCityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        String villeId = archives.requireVilleId(applicationCityId);
        return officialDay(villeId, date);
    }

    // --- helpers -------------------------------------------------------------

    private HabousCitySummaryResponse toSummary(CityCatalogueEntry entry, Set<String> availableVilles) {
        var support = citySupport.supportFor(entry.habousCityId());
        return new HabousCitySummaryResponse(
                entry.habousCityId(),
                entry.exactArabicName(),
                support.citySlug().orElse(null),
                support.citySlug().isPresent() && support.habousCityId().isPresent(),
                support.secApproachSupported(),
                availableVilles.contains(entry.habousCityId()));
    }

    /** Ville ids with at least one collected day in the most recent archive. */
    private Set<String> availableVilleIds() {
        return archiveRepository.listMonths().stream()
                .max(YearMonth::compareTo)
                .flatMap(archiveRepository::findArchive)
                .map(archive -> archive.cities().stream()
                        .filter(c -> c.daysAvailable() > 0)
                        .map(MonthlyCitySchedule::habousCityId)
                        .collect(Collectors.toSet()))
                .orElseGet(Set::of);
    }
}
