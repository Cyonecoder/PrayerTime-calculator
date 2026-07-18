package io.github.cyonecoder.salatapi.api.controller;

import io.github.cyonecoder.salatapi.api.exception.ApiErrorCode;
import io.github.cyonecoder.salatapi.api.exception.ApiException;
import io.github.cyonecoder.salatapi.verification.VerificationReportRepository;
import io.github.cyonecoder.salatapi.verification.model.CityDailyVerification;
import io.github.cyonecoder.salatapi.verification.model.DailyVerificationReport;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The verification endpoints (spec 23.14). */
@RestController
@RequestMapping("/api/v1/verification")
public class VerificationController {

    private final VerificationReportRepository reports;

    public VerificationController(VerificationReportRepository reports) {
        this.reports = reports;
    }

    @GetMapping("/latest")
    public DailyVerificationReport latest() {
        return reports.findLatest().orElseThrow(() ->
                new ApiException(ApiErrorCode.RESOURCE_NOT_FOUND, "No verification report has been produced yet"));
    }

    @GetMapping("/dates/{date}")
    public DailyVerificationReport byDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reports.find(date).orElseThrow(() ->
                new ApiException(ApiErrorCode.RESOURCE_NOT_FOUND, "No verification report for " + date));
    }

    @GetMapping("/dates/{date}/cities/{applicationCityId}")
    public CityDailyVerification byDateAndCity(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @PathVariable String applicationCityId) {
        DailyVerificationReport report = reports.find(date).orElseThrow(() ->
                new ApiException(ApiErrorCode.RESOURCE_NOT_FOUND, "No verification report for " + date));
        return report.cities().stream()
                .filter(c -> c.applicationCityId().equalsIgnoreCase(applicationCityId))
                .findFirst()
                .orElseThrow(() -> new ApiException(ApiErrorCode.RESOURCE_NOT_FOUND, 
                        "City '" + applicationCityId + "' was not in the verification intersection for " + date));
    }

    @GetMapping("/months/{year}/{month}")
    public List<DailyVerificationReport> byMonth(@PathVariable int year, @PathVariable int month) {
        return reports.findInMonth(year, month);
    }
}
