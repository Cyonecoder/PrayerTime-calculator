package io.github.cyonecoder.salatapi.application;

import io.github.cyonecoder.salatapi.api.dto.request.Precision;
import io.github.cyonecoder.salatapi.api.dto.response.PrayerScheduleResponse;
import io.github.cyonecoder.salatapi.api.dto.response.UnifiedPrayerTimesResponse;
import io.github.cyonecoder.salatapi.api.exception.ApiErrorCode;
import io.github.cyonecoder.salatapi.api.exception.ApiException;
import io.github.cyonecoder.salatapi.archive.model.ArchivedDay;
import io.github.cyonecoder.salatapi.common.model.Prayer;
import io.github.cyonecoder.salatapi.common.model.PrayerTimes;
import io.github.cyonecoder.salatapi.secapproach.service.SecApproachCalculator;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Computes sec-approach schedules and resolves the unified best-source request
 * (spec 23.10–23.13).
 */
@Service
public class PrayerTimesQueryService {

    /** The range endpoint's default hard cap (spec 23.11); overridable via salat.api.maximum-range-days. */
    public static final int MAX_RANGE_DAYS = 366;

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter HH_MM_SS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** How the unified endpoint resolved. */
    public enum ResolvedSource { HABOUS_OFFICIAL, SEC_APPROACH }

    private final SecApproachCalculator secApproach;
    private final MonthlyArchiveQueryService archives;
    private final int maximumRangeDays;

    public PrayerTimesQueryService(
            SecApproachCalculator secApproach, MonthlyArchiveQueryService archives,
            io.github.cyonecoder.salatapi.config.ApiProperties apiProperties) {
        this.secApproach = secApproach;
        this.archives = archives;
        this.maximumRangeDays = apiProperties.maximumRangeDays();
    }

    // --- sec-approach --------------------------------------------------------

    /** @return the sec-approach schedule for one date at the given precision (spec 23.10) */
    public PrayerScheduleResponse secApproachDay(String applicationCityId, LocalDate date, Precision precision) {
        return PrayerScheduleResponse.of(compute(applicationCityId, date, precision), precision);
    }

    /** @return the sec-approach schedules across a date range, inclusive (spec 23.11) */
    public List<PrayerScheduleResponse> secApproachRange(
            String applicationCityId, LocalDate from, LocalDate to, Precision precision) {
        if (to.isBefore(from)) {
            throw new ApiException(ApiErrorCode.INVALID_DATE, "'to' must not be before 'from'.");
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        if (days > maximumRangeDays) {
            throw new ApiException(ApiErrorCode.DATE_RANGE_TOO_LARGE,
                    "The requested range is " + days + " days; the maximum is " + maximumRangeDays + ".");
        }
        List<PrayerScheduleResponse> schedule = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            schedule.add(PrayerScheduleResponse.of(compute(applicationCityId, date, precision), precision));
        }
        return schedule;
    }

    /** @return the sec-approach schedules for a whole month (spec 23.12) */
    public List<PrayerScheduleResponse> secApproachMonth(
            String applicationCityId, int year, int month, Precision precision) {
        YearMonth ym;
        try {
            ym = YearMonth.of(year, month);
        } catch (java.time.DateTimeException e) {
            throw new ApiException(ApiErrorCode.INVALID_MONTH, ApiErrorCode.INVALID_MONTH.defaultDetail());
        }
        return secApproachRange(applicationCityId, ym.atDay(1), ym.atEndOfMonth(), precision);
    }

    // --- unified best --------------------------------------------------------

    /**
     * Resolves the unified request, never hiding a fallback (spec 23.13).
     *
     * @param applicationCityId the application city slug
     * @param date              the date
     * @param source            {@code official}, {@code sec-approach} or {@code best}
     * @param precision         minute or second
     * @return the resolved schedule with full source provenance
     */
    public UnifiedPrayerTimesResponse unified(
            String applicationCityId, LocalDate date, String source, Precision precision) {
        String requested = normalizeSource(source);
        return switch (requested) {
            case "OFFICIAL" -> official(applicationCityId, date, precision, "OFFICIAL");
            case "SEC_APPROACH" -> secApproachUnified(applicationCityId, date, precision, "SEC_APPROACH", false, null);
            case "BEST" -> best(applicationCityId, date, precision);
            default -> throw new ApiException(
                    ApiErrorCode.INVALID_SOURCE, ApiErrorCode.INVALID_SOURCE.defaultDetail());
        };
    }

    private UnifiedPrayerTimesResponse best(String applicationCityId, LocalDate date, Precision precision) {
        Optional<ArchivedDay> official = officialDay(applicationCityId, date);
        if (official.isPresent()) {
            return unifiedResponse(applicationCityId, date, precision, "BEST",
                    ResolvedSource.HABOUS_OFFICIAL.name(), false, null,
                    officialTimes(official.get(), date), false);
        }
        // Fall back to sec-approach, and say so.
        return secApproachUnified(applicationCityId, date, precision, "BEST", true,
                "Official data is unavailable for the requested date");
    }

    private UnifiedPrayerTimesResponse official(
            String applicationCityId, LocalDate date, Precision precision, String requested) {
        ArchivedDay day = officialDay(applicationCityId, date).orElseThrow(() ->
                new ApiException(ApiErrorCode.OFFICIAL_DATA_NOT_AVAILABLE,
                        "No official data for application city '" + applicationCityId + "' on " + date + "."));
        return unifiedResponse(applicationCityId, date, precision, requested,
                ResolvedSource.HABOUS_OFFICIAL.name(), false, null,
                officialTimes(day, date), false);
    }

    private UnifiedPrayerTimesResponse secApproachUnified(
            String applicationCityId, LocalDate date, Precision precision,
            String requested, boolean fallbackUsed, String fallbackReason) {
        PrayerTimes times = compute(applicationCityId, date, precision);
        return unifiedResponse(applicationCityId, date, precision, requested,
                ResolvedSource.SEC_APPROACH.name(), fallbackUsed, fallbackReason, times, precision == Precision.SECOND);
    }

    private UnifiedPrayerTimesResponse unifiedResponse(
            String applicationCityId, LocalDate date, Precision precision, String requested,
            String resolved, boolean fallbackUsed, String fallbackReason, PrayerTimes times, boolean second) {
        var format = second ? HH_MM_SS : HH_MM;
        Map<String, String> rendered = new LinkedHashMap<>();
        for (Prayer prayer : Prayer.values()) {
            rendered.put(prayer.key(), times.get(prayer).format(format));
        }
        return new UnifiedPrayerTimesResponse(
                applicationCityId, date, precision.key(), requested, resolved,
                fallbackUsed, fallbackReason, rendered);
    }

    // --- helpers -------------------------------------------------------------

    private PrayerTimes compute(String applicationCityId, LocalDate date, Precision precision) {
        return precision == Precision.SECOND
                ? secApproach.computeSecondPreciseTimes(applicationCityId, date)
                : secApproach.computeTimes(applicationCityId, date);
    }

    private Optional<ArchivedDay> officialDay(String applicationCityId, LocalDate date) {
        String villeId = archives.requireVilleId(applicationCityId);
        return archives.officialDay(villeId, date);
    }

    private PrayerTimes officialTimes(ArchivedDay day, LocalDate date) {
        return new PrayerTimes(date, day.fajr(), day.sunrise(), day.dhuhr(),
                day.asr(), day.maghrib(), day.isha());
    }

    private static String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return "BEST";
        }
        return source.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
    }
}
