package io.github.cyonecoder.prayertime.audit.dataset;

import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * One normalized official Habous day: the date and the six published times.
 *
 * <p>Immutable value object. All six times and the date are mandatory.
 *
 * @param date    the civil date
 * @param fajr    official Fajr
 * @param sunrise official Sunrise (Chourouk)
 * @param dhuhr   official Dhuhr
 * @param asr     official Asr
 * @param maghrib official Maghrib
 * @param isha    official Isha
 */
public record HabousReferenceDay(
        LocalDate date,
        LocalTime fajr,
        LocalTime sunrise,
        LocalTime dhuhr,
        LocalTime asr,
        LocalTime maghrib,
        LocalTime isha) {

    public HabousReferenceDay {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(fajr, "fajr");
        Objects.requireNonNull(sunrise, "sunrise");
        Objects.requireNonNull(dhuhr, "dhuhr");
        Objects.requireNonNull(asr, "asr");
        Objects.requireNonNull(maghrib, "maghrib");
        Objects.requireNonNull(isha, "isha");
    }

    /**
     * The published time for a prayer.
     *
     * @param prayer the prayer
     * @return the time
     */
    public LocalTime timeFor(Prayer prayer) {
        return switch (prayer) {
            case FAJR -> fajr;
            case SUNRISE -> sunrise;
            case DHUHR -> dhuhr;
            case ASR -> asr;
            case MAGHRIB -> maghrib;
            case ISHA -> isha;
        };
    }
}
