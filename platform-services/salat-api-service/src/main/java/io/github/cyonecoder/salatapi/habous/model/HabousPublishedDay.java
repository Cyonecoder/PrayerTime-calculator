package io.github.cyonecoder.salatapi.habous.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.cyonecoder.salatapi.common.model.Prayer;
import io.github.cyonecoder.salatapi.common.model.PrayerTimes;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * One day of an officially published Habous timetable, with its Gregorian date
 * already resolved from the Hijri-indexed source table.
 *
 * <p>These are the official times exactly as published — never calculated,
 * never adjusted, never rounded by this service.
 *
 * <p>Ported from {@code HabousLiveHtmlParser.HabousLiveDay} and
 * {@code audit.dataset.HabousReferenceDay}; see {@code docs/EXTRACTION_MAP.md}.
 *
 * @param date    the resolved Gregorian date
 * @param fajr    published Fajr
 * @param sunrise published sunrise
 * @param dhuhr   published Dhuhr
 * @param asr     published Asr
 * @param maghrib published Maghrib
 * @param isha    published Isha
 */
public record HabousPublishedDay(
        LocalDate date,
        LocalTime fajr,
        LocalTime sunrise,
        LocalTime dhuhr,
        LocalTime asr,
        LocalTime maghrib,
        LocalTime isha) {

    public HabousPublishedDay {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(fajr, "fajr");
        Objects.requireNonNull(sunrise, "sunrise");
        Objects.requireNonNull(dhuhr, "dhuhr");
        Objects.requireNonNull(asr, "asr");
        Objects.requireNonNull(maghrib, "maghrib");
        Objects.requireNonNull(isha, "isha");
    }

    /** @param prayer one of the six */
    public LocalTime get(Prayer prayer) {
        return switch (Objects.requireNonNull(prayer, "prayer")) {
            case FAJR -> fajr;
            case SUNRISE -> sunrise;
            case DHUHR -> dhuhr;
            case ASR -> asr;
            case MAGHRIB -> maghrib;
            case ISHA -> isha;
        };
    }

    /** @return these official times as a {@link PrayerTimes}, for comparison against sec-approach */
    @JsonIgnore
    public PrayerTimes asPrayerTimes() {
        return new PrayerTimes(date, fajr, sunrise, dhuhr, asr, maghrib, isha);
    }
}
