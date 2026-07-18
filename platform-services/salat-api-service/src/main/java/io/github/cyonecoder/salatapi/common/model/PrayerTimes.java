package io.github.cyonecoder.salatapi.common.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * The six published times for one city on one date, to the minute.
 *
 * <p>Ported from {@code domain.model.PrayerTimes}; see {@code docs/EXTRACTION_MAP.md}.
 *
 * @param date    the Gregorian date the times belong to
 * @param fajr    dawn prayer
 * @param sunrise sunrise (end of the Fajr window)
 * @param dhuhr   midday prayer
 * @param asr     afternoon prayer
 * @param maghrib sunset prayer
 * @param isha    night prayer
 */
public record PrayerTimes(
        LocalDate date,
        LocalTime fajr,
        LocalTime sunrise,
        LocalTime dhuhr,
        LocalTime asr,
        LocalTime maghrib,
        LocalTime isha) {

    public PrayerTimes {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(fajr, "fajr");
        Objects.requireNonNull(sunrise, "sunrise");
        Objects.requireNonNull(dhuhr, "dhuhr");
        Objects.requireNonNull(asr, "asr");
        Objects.requireNonNull(maghrib, "maghrib");
        Objects.requireNonNull(isha, "isha");
    }

    /**
     * @param prayer one of the six
     * @return the published time for that prayer
     */
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

    /** @return the six times keyed by prayer, in chronological order */
    public Map<Prayer, LocalTime> asMap() {
        Map<Prayer, LocalTime> map = new EnumMap<>(Prayer.class);
        for (Prayer prayer : Prayer.values()) {
            map.put(prayer, get(prayer));
        }
        return map;
    }

    /**
     * @param date  the date
     * @param times the six times keyed by prayer; all six must be present
     * @return the assembled schedule
     */
    public static PrayerTimes of(LocalDate date, Map<Prayer, LocalTime> times) {
        for (Prayer prayer : Prayer.values()) {
            if (!times.containsKey(prayer)) {
                throw new IllegalArgumentException("Missing time for " + prayer);
            }
        }
        return new PrayerTimes(
                date,
                times.get(Prayer.FAJR),
                times.get(Prayer.SUNRISE),
                times.get(Prayer.DHUHR),
                times.get(Prayer.ASR),
                times.get(Prayer.MAGHRIB),
                times.get(Prayer.ISHA));
    }
}
