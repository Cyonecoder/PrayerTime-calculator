package io.github.cyonecoder.prayertime.application.provider;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Transport-agnostic API layer over {@link PrayerTimesProvider}. It maps a
 * (city, date, source) request to a status + JSON body, so the same logic backs
 * the CLI and the HTTP adapter.
 *
 * <ul>
 *   <li>{@code 200} — served result (any mode that produced a value);</li>
 *   <li>{@code 400} — bad request (unknown city, bad date, bad source);</li>
 *   <li>{@code 503} — {@code HABOUS_OFFICIAL_DATA_UNAVAILABLE} in OFFICIAL mode.</li>
 * </ul>
 */
public final class PrayerTimesApiService {

    private static final Gson GSON =
            new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    /** An API response: HTTP status and JSON body. */
    public record Response(int status, String body) {
    }

    private final PrayerTimesProvider provider;

    public PrayerTimesApiService() {
        this(new HabousCompatiblePrayerTimesProvider());
    }

    public PrayerTimesApiService(PrayerTimesProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    /**
     * Handles {@code /prayer-times?date=&source=}.
     *
     * @param cityId the application city slug
     * @param date   the ISO date string
     * @param source the source-mode string (case-insensitive)
     * @return the response
     */
    public Response prayerTimes(String cityId, String date, String source) {
        LocalDate parsedDate;
        PrayerSourceMode mode;
        try {
            parsedDate = LocalDate.parse(date);
        } catch (DateTimeParseException | NullPointerException e) {
            return error(400, "BAD_DATE", "invalid or missing date: " + date);
        }
        try {
            mode = PrayerSourceMode.valueOf(source == null ? "AUTO" : source.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return error(400, "BAD_SOURCE", "unknown source mode: " + source);
        }
        return dispatch(cityId, parsedDate, mode);
    }

    /** Handles {@code /prayer-times/comparison?date=} (always COMPARE mode). */
    public Response comparison(String cityId, String date) {
        return prayerTimes(cityId, date, PrayerSourceMode.COMPARE.name());
    }

    private Response dispatch(String cityId, LocalDate date, PrayerSourceMode mode) {
        try {
            ProvidedTimes result = provider.get(cityId, date, mode);
            return new Response(200, ProvidedTimesJson.toJson(result));
        } catch (HabousDataUnavailableException e) {
            return error(HabousDataUnavailableException.HTTP_STATUS,
                    HabousDataUnavailableException.CODE, e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(400, "UNKNOWN_CITY", e.getMessage());
        }
    }

    private static Response error(int status, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", code);
        m.put("message", message);
        return new Response(status, GSON.toJson(m));
    }
}
