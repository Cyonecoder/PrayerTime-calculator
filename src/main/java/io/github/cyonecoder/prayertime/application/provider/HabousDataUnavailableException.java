package io.github.cyonecoder.prayertime.application.provider;

import java.time.LocalDate;

/**
 * Thrown by {@link PrayerSourceMode#OFFICIAL} mode when no valid official Habous
 * value exists for the requested city and date. It maps to the API status
 * {@code 503 HABOUS_OFFICIAL_DATA_UNAVAILABLE}. OFFICIAL mode never falls back to
 * calculation, so this is raised instead of returning a computed value.
 */
public final class HabousDataUnavailableException extends RuntimeException {

    /** Stable API error code. */
    public static final String CODE = "HABOUS_OFFICIAL_DATA_UNAVAILABLE";

    /** Suggested HTTP status. */
    public static final int HTTP_STATUS = 503;

    private final String cityId;
    private final LocalDate date;

    public HabousDataUnavailableException(String cityId, LocalDate date, String reason) {
        super(CODE + ": no official Habous data for city '" + cityId + "' on " + date
                + " (" + reason + ")");
        this.cityId = cityId;
        this.date = date;
    }

    public String cityId() {
        return cityId;
    }

    public LocalDate date() {
        return date;
    }
}
