package io.github.cyonecoder.salatapi.archive.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * One published day in a monthly archive, with the provenance of the observation
 * it came from (spec 17).
 *
 * <p>The times are the official ones exactly as published — never calculated,
 * adjusted or rounded by this service.
 *
 * <p>{@code firstObservedAt} and {@code lastObservedAt} bracket when this
 * city/date row was seen. They differ once the daily sync has seen the same date
 * on several days, which is the normal case: a Hijri page stays published for
 * weeks, so each of its dates is re-observed many times. {@code sourceChecksum}
 * identifies the page the published times were taken from, and resolves to an
 * immutable raw snapshot.
 *
 * @param date            the Gregorian date, which alone decides the archive it belongs to
 * @param fajr            published Fajr
 * @param sunrise         published sunrise
 * @param dhuhr           published Dhuhr
 * @param asr             published Asr
 * @param maghrib         published Maghrib
 * @param isha            published Isha
 * @param sourceChecksum  SHA-256 of the source page these times came from
 * @param firstObservedAt when this city/date was first seen
 * @param lastObservedAt  when this city/date was last seen
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ArchivedDay(
        LocalDate date,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm") LocalTime fajr,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm") LocalTime sunrise,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm") LocalTime dhuhr,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm") LocalTime asr,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm") LocalTime maghrib,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm") LocalTime isha,
        String sourceChecksum,
        Instant firstObservedAt,
        Instant lastObservedAt) {

    public ArchivedDay {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(fajr, "fajr");
        Objects.requireNonNull(sunrise, "sunrise");
        Objects.requireNonNull(dhuhr, "dhuhr");
        Objects.requireNonNull(asr, "asr");
        Objects.requireNonNull(maghrib, "maghrib");
        Objects.requireNonNull(isha, "isha");
        Objects.requireNonNull(firstObservedAt, "firstObservedAt");
        Objects.requireNonNull(lastObservedAt, "lastObservedAt");
    }
}
