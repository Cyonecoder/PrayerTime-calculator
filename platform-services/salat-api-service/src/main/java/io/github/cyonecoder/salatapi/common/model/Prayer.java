package io.github.cyonecoder.salatapi.common.model;

import java.util.Locale;
import java.util.Optional;

/**
 * The six daily times produced by the calculator, in chronological order.
 *
 * <p>Note that {@code SUNRISE} is not a prayer but is included because it marks
 * the end of the Fajr window and is part of the standard published schedule.
 *
 * <p>Ported from {@code io.github.cyonecoder.prayertime.domain.model.Prayer};
 * see {@code docs/EXTRACTION_MAP.md}. The {@link #key()} form is the lowercase
 * name used by the sec-approach offsets resource.
 */
public enum Prayer {
    /** Dawn prayer. */
    FAJR,
    /** Sunrise (end of the Fajr window). */
    SUNRISE,
    /** Midday prayer. */
    DHUHR,
    /** Afternoon prayer. */
    ASR,
    /** Sunset prayer. */
    MAGHRIB,
    /** Night prayer. */
    ISHA;

    /** @return the lowercase key used in the offsets resource (e.g. {@code "fajr"}) */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * @param value a prayer name in any case
     * @return the prayer, or empty if the name is not one of the six
     */
    public static Optional<Prayer> fromKey(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (Prayer prayer : values()) {
            if (prayer.key().equals(value.trim().toLowerCase(Locale.ROOT))) {
                return Optional.of(prayer);
            }
        }
        return Optional.empty();
    }
}
