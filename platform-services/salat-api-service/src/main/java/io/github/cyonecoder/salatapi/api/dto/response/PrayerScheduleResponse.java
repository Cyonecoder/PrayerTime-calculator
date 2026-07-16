package io.github.cyonecoder.salatapi.api.dto.response;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.github.cyonecoder.salatapi.api.dto.request.Precision;
import io.github.cyonecoder.salatapi.common.model.Prayer;
import io.github.cyonecoder.salatapi.common.model.PrayerTimes;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A day's six prayer times, formatted to the requested precision.
 *
 * <p>Times are rendered {@code HH:mm} for minute precision and {@code HH:mm:ss}
 * for second precision, so the {@code precision} field and the value format always
 * agree.
 *
 * @param date      the Gregorian date
 * @param precision {@code minute} or {@code second}
 * @param times     the six prayers keyed fajr..isha, in chronological order
 */
@JsonPropertyOrder({"date", "precision", "times"})
public record PrayerScheduleResponse(LocalDate date, String precision, Map<String, String> times) {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter HH_MM_SS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * @param times     the schedule
     * @param precision the precision the times were computed at
     * @return the formatted response
     */
    public static PrayerScheduleResponse of(PrayerTimes times, Precision precision) {
        DateTimeFormatter format = precision == Precision.SECOND ? HH_MM_SS : HH_MM;
        Map<String, String> rendered = new LinkedHashMap<>();
        for (Prayer prayer : Prayer.values()) {
            rendered.put(prayer.key(), format(times.get(prayer), format));
        }
        return new PrayerScheduleResponse(times.date(), precision.key(), rendered);
    }

    private static String format(LocalTime time, DateTimeFormatter format) {
        return time.format(format);
    }
}
