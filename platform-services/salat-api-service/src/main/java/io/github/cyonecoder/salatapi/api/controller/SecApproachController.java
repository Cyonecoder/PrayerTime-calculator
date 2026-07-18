package io.github.cyonecoder.salatapi.api.controller;

import io.github.cyonecoder.salatapi.api.dto.request.Precision;
import io.github.cyonecoder.salatapi.api.dto.response.PrayerScheduleResponse;
import io.github.cyonecoder.salatapi.api.dto.response.SecApproachInfoResponse;
import io.github.cyonecoder.salatapi.application.PrayerTimesQueryService;
import io.github.cyonecoder.salatapi.city.MoroccoCityRegistry;
import io.github.cyonecoder.salatapi.secapproach.configuration.SecApproachParameters;
import io.github.cyonecoder.salatapi.verification.model.DailyVerificationReport;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The sec-approach calculation endpoints (spec 23.8–23.12). Served entirely from
 * the released v2 parameters; never from official data.
 */
@RestController
@RequestMapping("/api/v1/sec-approach")
public class SecApproachController {

    private final PrayerTimesQueryService prayerTimes;
    private final SecApproachParameters parameters;
    private final MoroccoCityRegistry registry;

    public SecApproachController(
            PrayerTimesQueryService prayerTimes,
            SecApproachParameters parameters,
            MoroccoCityRegistry registry) {
        this.prayerTimes = prayerTimes;
        this.parameters = parameters;
        this.registry = registry;
    }

    /** One sec-approach city (spec 23.9). */
    public record SecApproachCity(String applicationCityId, String displayName, String habousCityId) {}

    /** Spec 23.8. */
    @GetMapping("/info")
    public SecApproachInfoResponse info() {
        return new SecApproachInfoResponse(
                parameters.parameterVersion(),
                parameters.count(),
                parameters.model(),
                "Africa/Casablanca",
                List.of(Precision.MINUTE.key(), Precision.SECOND.key()),
                parameters.validatedWindowStart() + " through " + parameters.validatedWindowEnd(),
                DailyVerificationReport.ACCURACY_DISCLAIMER);
    }

    /** Exactly the cities present in the released v2 offsets resource (spec 23.9). */
    @GetMapping("/cities")
    public List<SecApproachCity> cities() {
        return parameters.supportedCitySlugs().stream()
                .sorted()
                .map(slug -> new SecApproachCity(
                        slug,
                        registry.findBySlug(slug).map(c -> c.displayName()).orElse(slug),
                        registry.findBySlug(slug).flatMap(c -> c.habousCityId()).orElse(null)))
                .toList();
    }

    /** Spec 23.10. */
    @GetMapping("/cities/{applicationCityId}/prayer-times")
    public PrayerScheduleResponse day(
            @PathVariable String applicationCityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "minute") String precision) {
        return prayerTimes.secApproachDay(applicationCityId, date, Precision.parse(precision));
    }

    /** Spec 23.11. */
    @GetMapping("/cities/{applicationCityId}/prayer-times/range")
    public List<PrayerScheduleResponse> range(
            @PathVariable String applicationCityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "minute") String precision) {
        return prayerTimes.secApproachRange(applicationCityId, from, to, Precision.parse(precision));
    }

    /** Spec 23.12. */
    @GetMapping("/cities/{applicationCityId}/prayer-times/month")
    public List<PrayerScheduleResponse> month(
            @PathVariable String applicationCityId,
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(defaultValue = "minute") String precision) {
        return prayerTimes.secApproachMonth(applicationCityId, year, month, Precision.parse(precision));
    }
}
