package io.github.cyonecoder.salatapi.api.controller;

import io.github.cyonecoder.salatapi.api.dto.request.Precision;
import io.github.cyonecoder.salatapi.api.dto.response.UnifiedPrayerTimesResponse;
import io.github.cyonecoder.salatapi.application.PrayerTimesQueryService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The unified application-city endpoint (spec 23.13). Resolves official-vs-
 * sec-approach by the requested source and <strong>never hides a fallback</strong>.
 */
@RestController
@RequestMapping("/api/v1/prayer-times")
public class PrayerTimesController {

    private final PrayerTimesQueryService prayerTimes;

    public PrayerTimesController(PrayerTimesQueryService prayerTimes) {
        this.prayerTimes = prayerTimes;
    }

    @GetMapping("/cities/{applicationCityId}")
    public UnifiedPrayerTimesResponse prayerTimes(
            @PathVariable String applicationCityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "best") String source,
            @RequestParam(defaultValue = "minute") String precision) {
        return prayerTimes.unified(applicationCityId, date, source, Precision.parse(precision));
    }
}
