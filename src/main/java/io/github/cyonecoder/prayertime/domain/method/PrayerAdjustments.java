package io.github.cyonecoder.prayertime.domain.method;

import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.util.Objects;

/**
 * Per-prayer time corrections, in whole minutes, applied on top of the raw
 * astronomical result.
 *
 * <p>These are <strong>method-level configuration</strong>, not part of the
 * generic astronomy. For example the Moroccan schedule adds a small buffer to
 * Dhuhr and Maghrib; those buffers belong here (in the method's parameters),
 * never inside the mathematical engine.
 *
 * <p>Immutable value object. Values may be positive, negative or zero.
 *
 * @param fajr    minutes added to Fajr
 * @param sunrise minutes added to Sunrise
 * @param dhuhr   minutes added to Dhuhr
 * @param asr     minutes added to Asr
 * @param maghrib minutes added to Maghrib
 * @param isha    minutes added to Isha
 */
public record PrayerAdjustments(
        int fajr,
        int sunrise,
        int dhuhr,
        int asr,
        int maghrib,
        int isha) {

    /** No adjustment for any prayer. */
    public static final PrayerAdjustments NONE = new PrayerAdjustments(0, 0, 0, 0, 0, 0);

    /**
     * Returns the adjustment in minutes for the given prayer.
     *
     * @param prayer the prayer to look up
     * @return the adjustment in minutes
     * @throws NullPointerException if {@code prayer} is {@code null}
     */
    public int minutesFor(Prayer prayer) {
        Objects.requireNonNull(prayer, "prayer");
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
